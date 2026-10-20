package com.sari.subtitle.v2

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DeepLTranslationService {

    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")

    /**
     * يترجم SRT كاملاً باستخدام DeepL.
     */
    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        require(apiKey.isNotBlank()) { "أدخل مفتاح DeepL من الإعدادات أولاً" }
        require(sourceLang.isNotBlank() && targetLang.isNotBlank()) { "اللغة مطلوبة" }

        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))

        require(blocks.isNotEmpty() && blocks.any { timestampPattern.containsMatchIn(it) }) {
            "الملف لا يبدو بصيغة SRT صحيحة"
        }

        val translatable = blocks.count { block ->
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            ti >= 0 && lines.drop(ti + 1).any { it.isNotBlank() }
        }

        var completed = 0
        val output = ArrayList<String>(blocks.size)

        for (block in blocks) {
            val lines = block.split('\n').toMutableList()
            val timeIndex = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (timeIndex < 0 || lines.drop(timeIndex + 1).all { it.isBlank() }) {
                output.add(block)
                continue
            }

            val text = lines.drop(timeIndex + 1).joinToString("\n").trim()
            val translated = try {
                translateText(text, sourceLang, targetLang, apiKey)
            } catch (e: Exception) {
                // نُرجع النص الأصلي إذا فشلت الترجمة
                text
            }

            while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
            lines.add(translated)
            output.add(lines.joinToString("\n"))
            completed++
            onProgress(completed, translatable)
        }
        return output.joinToString("\n\n") + "\n"
    }

    /**
     * ترجمة نص واحد عبر DeepL.
     */
    private fun translateText(text: String, sourceLang: String, targetLang: String, apiKey: String): String {
        if (text.isBlank()) return text

        // DeepL Free endpoint
        val url = URL("https://api-free.deepl.com/v2/translate")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "SariSubtitle/1.0")

            val postData = buildString {
                append("text=").append(URLEncoder.encode(text, "UTF-8"))
                append("&source_lang=").append(sourceLang.uppercase().take(2))
                append("&target_lang=").append(targetLang.uppercase().take(2))
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(postData) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

            if (responseCode !in 200..299) {
                throw Exception("DeepL HTTP $responseCode: ${response.take(300)}")
            }

            val json = JSONObject(response)
            val translations = json.optJSONArray("translations") ?: return text
            val first = translations.optJSONObject(0) ?: return text
            return first.optString("text", text)
        } finally {
            connection.disconnect()
        }
    }
}
