package com.sari.subtitle.v2

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Deepgram prerecorded audio transcription (Nova-3); call off the UI thread. */
class DeepgramTranscriptionService(private val context: Context, private val apiKeyProvider: () -> String) {
    data class Result(val language: String, val text: String, val srt: String, val segmentCount: Int)
    fun transcribe(uri: Uri, language: String): Result {
        val key = apiKeyProvider().trim()
        require(key.isNotEmpty()) { "أدخل مفتاح Deepgram من الإعدادات أولاً." }
        require(language in listOf("ar", "en", "auto")) { "لغة غير مدعومة." }
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        val mime = when (ext) { "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "mp4" -> "video/mp4"; "webm" -> "audio/webm"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; else -> "application/octet-stream" }
        val temp = File.createTempFile("deepgram_", ".${ext.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"}", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                ?: throw IOException("تعذر فتح ملف الصوت.")
            require(temp.length() in 1L..(24L * 1024L * 1024L)) { "يجب أن يكون حجم الملف بين 1 بايت و24 MB." }
            val params = mutableListOf("model=nova-3", "smart_format=true", "utterances=true", "punctuate=true")
            if (language == "auto") params += "detect_language=true" else params += "language=${URLEncoder.encode(language, "UTF-8")}"
            val conn = (URL("https://api.deepgram.com/v1/listen?${params.joinToString("&")}").openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"; conn.connectTimeout = 30000; conn.readTimeout = 180000
                conn.doOutput = true; conn.setRequestProperty("Authorization", "Token $key"); conn.setRequestProperty("Content-Type", mime)
                conn.setFixedLengthStreamingMode(temp.length())
                conn.outputStream.use { out -> temp.inputStream().use { it.copyTo(out) } }
                val status = conn.responseCode
                val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(body).optJSONObject("err")?.optString("message") ?: JSONObject(body).optString("message") }.getOrNull().orEmpty()
                    throw IOException("فشل Deepgram (HTTP $status): ${message.ifBlank { body.take(400) }}")
                }
                return parse(body, language)
            } finally { conn.disconnect() }
        } finally { temp.delete() }
    }
    private fun parse(body: String, requested: String): Result {
        val root = JSONObject(body); val results = root.optJSONObject("results") ?: throw IOException("استجابة Deepgram غير مكتملة.")
        val channel = results.optJSONArray("channels")?.optJSONObject(0) ?: throw IOException("لم يُرجع Deepgram قناة صوتية.")
        val alt = channel.optJSONArray("alternatives")?.optJSONObject(0) ?: throw IOException("لم يُرجع Deepgram نصاً.")
        val utterances = results.optJSONArray("utterances")
        val cues = mutableListOf<String>(); val full = StringBuilder()
        if (utterances != null && utterances.length() > 0) {
            for (i in 0 until utterances.length()) {
                val u = utterances.optJSONObject(i) ?: continue
                val start = u.optDouble("start", Double.NaN); val end = u.optDouble("end", Double.NaN); val text = u.optString("transcript", "").trim()
                if (start.isFinite() && end.isFinite() && start >= 0 && end > start && text.isNotBlank()) {
                    cues += "${cues.size + 1}\n${time(start)} --> ${time(end)}\n$text"; if (full.isNotEmpty()) full.append(' '); full.append(text)
                }
            }
        }
        if (cues.isEmpty()) {
            val words = alt.optJSONArray("words") ?: throw IOException("لم يُرجع Deepgram توقيتات للكلمات.")
            var chunk = mutableListOf<org.json.JSONObject>()
            fun flush() {
                if (chunk.isEmpty()) return
                val start = chunk.first().optDouble("start", 0.0); val end = chunk.last().optDouble("end", start + 0.2)
                val text = chunk.joinToString(" ") { it.optString("punctuated_word", it.optString("word", "")) }.trim()
                if (end > start && text.isNotBlank()) { cues += "${cues.size + 1}\n${time(start)} --> ${time(end)}\n$text"; if (full.isNotEmpty()) full.append(' '); full.append(text) }
                chunk = mutableListOf()
            }
            for (i in 0 until words.length()) { words.optJSONObject(i)?.let { chunk += it }; if (chunk.size >= 7) flush() }; flush()
        }
        if (cues.isEmpty()) throw IOException("لم يتم العثور على مقاطع صالحة لإنشاء SRT.")
        val detected = channel.optString("detected_language", requested)
        return Result(detected, alt.optString("transcript", full.toString()).trim(), cues.joinToString("\n\n") + "\n", cues.size)
    }
    private fun time(seconds: Double): String { val ms = (seconds * 1000).toLong().coerceAtLeast(0); return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000) }
    private fun displayName(uri: Uri): String = try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "audio.bin" } ?: "audio.bin" } catch (_: Exception) { "audio.bin" }
}
