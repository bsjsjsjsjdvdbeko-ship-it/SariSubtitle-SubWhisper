package com.sari.subtitle.v2

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * يكتشف لغة النص تلقائياً عبر Google Translate API.
 */
object LanguageDetector {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * يكتشف لغة النص.
     * @return رمز اللغة (مثل "ru"، "en"، "ar")، أو null عند الفشل.
     */
    fun detect(text: String): String? {
        if (text.isBlank()) return null

        return try {
            // نأخذ أول 200 حرف فقط
            val sample = text.take(200).replace("\n", " ").replace("\r", " ")
            val encoded = URLEncoder.encode(sample, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=en&dt=t&q=$encoded"

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val response = reader.use { it.readText() }
            conn.disconnect()

            parseLanguage(response)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يستخرج رمز اللغة من استجابة Google Translate.
     * البنية: [[[...]], ..., "ru", ...]
     */
    private fun parseLanguage(response: String): String? {
        return try {
            val arr = JSONArray(response)
            // العنصر index 2 عادة يحتوي رمز اللغة
            if (arr.length() > 2) {
                val lang = arr.optString(2, "")
                if (lang.isNotBlank() && lang.length in 2..5) return lang
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
