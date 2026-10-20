package com.sari.subtitle.v2

import org.json.JSONArray
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
    private val cyrillicRegex = Regex("[\\u0400-\\u04FF]")

    /**
     * ترجمة موثوقة متعددة المزودين:
     * 1. DeepL (إذا مفتاح موجود)
     * 2. Google Translate (مجاني)
     * 3. MyMemory (احتياطي أخير)
     */
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

            val originalText = lines.drop(timeIndex + 1).joinToString("\n").trim()
            var translated = originalText

            // 🔵 الطبقة 1: DeepL (إذا مفتاح متاح)
            if (apiKey.isNotBlank()) {
                translated = tryDeepL(originalText, sourceLang, targetLang, apiKey)
                // إذا نجح DeepL — تحقق من الجودة
                if (translated.isNotBlank() && !translated.contains("???") && translated != originalText) {
                    // نجح
                } else {
                    translated = originalText // إعادة تعيين للمحاولة التالية
                }
            }

            // 🟢 الطبقة 2: Google Translate (مجاني)
            if (translated == originalText) {
                translated = tryGoogleTranslate(originalText, targetLang)
                if (translated.isNotBlank() && !translated.contains("???") && translated != originalText) {
                    // نجح
                } else {
                    translated = originalText
                }
            }

            // 🟡 الطبقة 3: MyMemory (احتياطي أخير)
            if (translated == originalText) {
                translated = tryMyMemory(originalText, sourceLang, targetLang)
                if (translated.isNotBlank() && !translated.contains("???") && translated != originalText) {
                    // نجح
                } else {
                    translated = originalText
                }
            }

            // 🔴 الطبقة 4: إذا فشل كل شيء — نحتفظ بالنص الأصلي
            // لكن نُحوّل أي ???? إلى النص الأصلي
            if (translated.contains("???") || translated.isBlank()) {
                translated = originalText
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
     * DeepL — الأساسي (جودة عالية للأوروبية)
     */
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

            val source = sourceLang.uppercase().take(2)
            val target = targetLang.uppercase().take(2)
            val postData = "text=${URLEncoder.encode(text, "UTF-8")}" +
                    "&source_lang=$source" +
                    "&target_lang=$target"

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

    /**
     * Google Translate — مجاني (يدعم كل اللغات)
     */
    private fun tryGoogleTranslate(text: String, targetLang: String): String {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return ""

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val response = reader.use { it.readText() }
            val arr = JSONArray(response)
            val sentences = arr.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i) ?: continue
                val translated = item.optString(0, "")
                if (translated.isNotBlank()) {
                    sb.append(translated)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * MyMemory — احتياطي (بدون مفتاح)
     */
    private fun tryMyMemory(text: String, sourceLang: String, targetLang: String): String {
        return try {
            if (text.length > 450) return "" // حد MyMemory
            val q = URLEncoder.encode(text, "UTF-8")
            val pair = URLEncoder.encode("$sourceLang|$targetLang", "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return ""

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val response = reader.use { it.readText() }

            val json = JSONObject(response)
            val status = json.optInt("responseStatus", 200)
            if (status != 200) return ""

            json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
