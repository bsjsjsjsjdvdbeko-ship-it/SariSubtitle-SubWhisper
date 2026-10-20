package com.sari.subtitle.v2

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DeepLTranslationService {

    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")
    private val arabicRegex = Regex("[\\u0600-\\u06FF]")

    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
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
            var translated = text

            // 1. محاولة DeepL (إذا كان المفتاح متاحاً)
            if (apiKey.isNotBlank()) {
                translated = tryDeepL(text, sourceLang, targetLang, apiKey)
            }

            // 2. التحقق: إذا فشل DeepL (نتيجة فارغة، أو تحتوي ????، أو نفس النص الأصلي)
            if (translated.isBlank() || translated.contains("???") || translated == text) {
                // 3. استخدام Google Translate كاحتياطي (يدعم كل اللغات)
                translated = tryGoogleTranslate(text, targetLang)
            }

            // 4. إذا فشل كلاهما، اترك النص الأصلي (لكن تأكد أنه ليس ????)
            if (translated.isBlank() || translated.contains("???")) {
                translated = if (arabicRegex.containsMatchIn(text)) text else "..."
            }

            while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
            lines.add(translated)
            output.add(lines.joinToString("\n"))
            completed++
            onProgress(completed, translatable)
        }
        return output.joinToString("\n\n") + "\n"
    }

    private fun tryDeepL(text: String, sourceLang: String, targetLang: String, apiKey: String): String {
        return try {
            val url = URL("https://api-free.deepl.com/v2/translate")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "text=${URLEncoder.encode(text, "UTF-8")}" +
                    "&source_lang=${sourceLang.uppercase().take(2)}" +
                    "&target_lang=${targetLang.uppercase().take(2)}"

            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(postData) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

            if (responseCode !in 200..299) return ""

            val json = JSONObject(response)
            val translations = json.optJSONArray("translations") ?: return ""
            translations.optJSONObject(0)?.optString("text", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun tryGoogleTranslate(text: String, targetLang: String): String {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val response = reader.use { it.readText() }
            val arr = org.json.JSONArray(response)
            val sentences = arr.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i) ?: continue
                sb.append(item.optString(0, ""))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }
}
