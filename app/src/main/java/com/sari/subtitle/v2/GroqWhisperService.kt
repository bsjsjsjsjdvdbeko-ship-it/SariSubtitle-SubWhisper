package com.sari.subtitle.v2

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** تفريغ ملف صوتي محلي بطلب واحد إلى Groq Whisper. */
class GroqWhisperService(
    private val context: Context,
    private val apiKeyProvider: () -> String
) {
    companion object {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MAX_UPLOAD_BYTES = 24L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 180_000
        private const val WRITE_TIMEOUT_MS = 180_000
    }

    data class Result(val language: String, val text: String, val srt: String, val segmentCount: Int)

    /** استدعِ هذه الدالة من thread خلفي، وليس Main Thread. */
    fun transcribe(audioUri: Uri, language: String): Result {
        val apiKey = apiKeyProvider().trim()
        require(apiKey.isNotEmpty()) { "أدخل مفتاح Groq من الإعدادات أولاً." }
        // "auto" means let Whisper detect the spoken language. In that case
        // omit the optional API language field instead of sending "auto" as a code.
        val lang = language.trim().lowercase(Locale.ROOT)
        require(lang == "ar" || lang == "en" || lang == "auto" || lang.isBlank()) {
            "اختر العربية أو الإنجليزية أو اكتشاف اللغة تلقائياً."
        }
        val originalName = getDisplayName(audioUri).ifBlank { "audio.wav" }
        val suffix = originalName.substringAfterLast('.', "wav").lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "wav"
        val tempFile = File.createTempFile("groq_upload_", ".$suffix", context.cacheDir)
        try {
            copyUriToFile(audioUri, tempFile)
            require(tempFile.length() > 0L) { "الملف الصوتي فارغ أو تعذر قراءته." }
            require(tempFile.length() <= MAX_UPLOAD_BYTES) {
                "حجم الملف أكبر من 24 MB. لم يُرسل الملف؛ التقطيع غير مفعّل في هذه المرحلة."
            }
            return sendRequest(tempFile, safeFileName(originalName), apiKey, lang)
        } finally {
            tempFile.delete()
        }
    }

    private fun sendRequest(
        file: File,
        fileName: String,
        apiKey: String,
        language: String
    ): Result {
        val boundary = "SariGroqBoundary${System.nanoTime()}"
        
        // ─── حساب حجم multipart مسبقاً ───
        val textParts = mutableListOf(
            "model" to "whisper-large-v3",
            "temperature" to "0",
            "response_format" to "verbose_json",
            "timestamp_granularities[]" to "segment"
        )
        // Groq/Whisper expects a language code such as "en" or "ar"; "auto"
        // is not a valid language code. Omit it so the model detects language.
        if (language.equals("ar", ignoreCase = true) || language.equals("en", ignoreCase = true)) {
            textParts.add(3, "language" to language.lowercase(Locale.ROOT))
        }
        
        var totalSize = 0L
        for ((name, value) in textParts) {
            totalSize += "--$boundary\r\n".toByteArray(Charsets.UTF_8).size
            totalSize += "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8).size
            totalSize += value.toByteArray(Charsets.UTF_8).size
            totalSize += "\r\n".toByteArray(Charsets.UTF_8).size
        }
        
        val safeName = safeFileName(fileName)
        val fileHeader = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(safeName).append("\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        totalSize += fileHeader.size
        totalSize += file.length()
        totalSize += "\r\n".toByteArray(Charsets.UTF_8).size
        totalSize += "--$boundary--\r\n".toByteArray(Charsets.UTF_8).size
        
        // ─── الاتصال ───
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            
            // ─── إجبار Content-Length ───
            connection.setFixedLengthStreamingMode(totalSize)
            
            BufferedOutputStream(connection.outputStream, 8192).use { output ->
                // text parts
                for ((name, value) in textParts) {
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                
                // file header
                output.write(fileHeader)
                
                // file content
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                    }
                }
                
                // footer
                output.write("\r\n".toByteArray(Charsets.UTF_8))
                output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
            
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            
            if (statusCode !in 200..299) {
                val message = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: responseBody.take(500)
                } catch (_: Exception) {
                    responseBody.take(500)
                }
                throw IOException("فشل Groq (HTTP $statusCode): $message")
            }
            
            return parseResponse(responseBody, language)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(body: String, requestedLanguage: String): Result {
        val json = JSONObject(body)
        val segments = json.optJSONArray("segments")
            ?: throw IOException("لم يُرجع Groq قائمة segments في الاستجابة.")
        val entries = mutableListOf<String>()
        val allText = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val start = segment.optDouble("start", Double.NaN)
            val end = segment.optDouble("end", Double.NaN)
            val text = segment.optString("text", "").trim()
            if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start || text.isBlank()) continue
            entries += "${entries.size + 1}\n${formatSrtTime(start)} --> ${formatSrtTime(end)}\n$text"
            if (allText.isNotEmpty()) allText.append(' ')
            allText.append(text)
        }
        if (entries.isEmpty()) throw IOException("لم توجد مقاطع صالحة في استجابة Groq.")
        return Result(
            language = json.optString("language", requestedLanguage),
            text = json.optString("text", allText.toString()).trim(),
            srt = entries.joinToString("\n\n") + "\n",
            segmentCount = entries.size
        )
    }

    private fun formatSrtTime(seconds: Double): String {
        val ms = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000L,
            (ms % 3_600_000L) / 60_000L, (ms % 60_000L) / 1_000L, ms % 1_000L)
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("تعذر فتح الملف الصوتي المحدد.")
        source.use { input ->
            FileOutputStream(destination).use { target ->
                BufferedInputStream(input).use { bufferedInput ->
                    BufferedOutputStream(target).use { bufferedOutput -> bufferedInput.copyTo(bufferedOutput) }
                }
            }
        }
    }

    private fun getDisplayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index).orEmpty() else ""
            } else ""
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    } catch (_: Exception) { uri.lastPathSegment.orEmpty() }

    private fun safeFileName(name: String): String = name
        .replace("\\", "_").replace("/", "_").replace("\"", "_")
        .replace("\r", "_").replace("\n", "_").take(100).ifBlank { "audio.wav" }
}
