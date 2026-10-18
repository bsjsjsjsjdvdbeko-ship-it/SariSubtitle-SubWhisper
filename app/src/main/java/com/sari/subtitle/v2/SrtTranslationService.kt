package com.sari.subtitle.v2

import android.text.Html
import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ترجمة SRT من داخل التطبيق.
 * - ترجمة تلقائية: يكتشف اللغة ثم يترجم إلى العربية.
 * - أو ترجمة يدوية: المستخدم يحدد اللغات.
 * - MyMemory API للترجمة (مجاني).
 * - لا يتم إرسال توقيتات SRT إلى الخدمة.
 */
object SrtTranslationService {

    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")

    /**
     * ترجمة تلقائية: يكتشف اللغة من أول نص، ثم يترجم إلى الهدف.
     * @param onDetected callback يُستدعى باللغة المكتشفة
     */
    fun translateAuto(
        source: String,
        targetLang: String = "ar",
        onDetected: (String) -> Unit = {},
        context: Context? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        // 1. استخرج النص الأول للكشف
        val firstText = extractFirstText(source)
            ?: throw IllegalArgumentException("الملف لا يحتوي على نصوص صالحة")

        // 2. اكتشف اللغة
        val detected = LanguageDetector.detect(firstText)
            ?: throw IllegalArgumentException("تعذر اكتشاف لغة الملف")

        onDetected(detected)

        // 3. إذا كانت اللغة الهدف، أعد كما هو
        if (detected.equals(targetLang, ignoreCase = true)) {
            return source
        }

        // 4. ترجم
        return translateSrt(source, detected, targetLang, context, onProgress)
    }

    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
        context: Context? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        require(sourceLang.isNotBlank() && targetLang.isNotBlank()) {
            "اختر لغة المصدر واللغة المستهدفة"
        }
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))

        require(blocks.isNotEmpty() && blocks.any { timestampPattern.containsMatchIn(it) }) {
            "الملف لا يبدو بصيغة SRT صحيحة"
        }

        // Cache persistent across runs: repeated subtitle lines do not consume API calls again.
        val memoryCache = mutableMapOf<String, String>()
        val output = ArrayList<String>(blocks.size)
        val translatable = blocks.count { block ->
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            ti >= 0 && lines.drop(ti + 1).any { it.isNotBlank() }
        }

        var completed = 0
        for (block in blocks) {
            val lines = block.split('\n').toMutableList()
            val timeIndex = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (timeIndex < 0 || lines.drop(timeIndex + 1).all { it.isBlank() }) {
                output.add(block)
                continue
            }
            val text = lines.drop(timeIndex + 1).joinToString("\n").trim()
            val key = cacheKey(text, sourceLang, targetLang)
            val translated = memoryCache[text] ?: readPersistentCache(context, key)
                ?: translateText(text, sourceLang, targetLang).also {
                    memoryCache[text] = it
                    writePersistentCache(context, key, it)
                }
            while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
            lines.add(translated)
            output.add(lines.joinToString("\n"))
            completed++
            onProgress(completed, translatable)
        }
        return output.joinToString("\r\n\r\n") + "\r\n"
    }

    /**
     * يستخرج أول نص من SRT (للكشف).
     */
    private fun extractFirstText(source: String): String? {
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\\n[ \\t]*\\n"))
        for (block in blocks) {
            val lines = block.split('\n')
            val ti = lines.indexOfFirst { timestampPattern.containsMatchIn(it) }
            if (ti >= 0) {
                val text = lines.drop(ti + 1).joinToString(" ").trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun cacheKey(text: String, sourceLang: String, targetLang: String): String {
        val raw = "$sourceLang|$targetLang|$text".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readPersistentCache(context: Context?, key: String): String? {
        if (context == null) return null
        return try {
            context.getSharedPreferences("srt_translation_cache_v1", Context.MODE_PRIVATE)
                .getString(key, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun writePersistentCache(context: Context?, key: String, value: String) {
        if (context == null || value.isBlank()) return
        try {
            context.getSharedPreferences("srt_translation_cache_v1", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        } catch (_: Exception) { /* Cache failures must not fail translation. */ }
    }

    private fun translateText(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text
        if (text.length > 450) {
            throw IllegalArgumentException("أحد مقاطع الترجمة طويل جداً؛ قصّره أو قسّمه")
        }

        var attempt = 0
        while (true) {
            try {
                return translateTextOnce(text, sourceLang, targetLang)
            } catch (e: Exception) {
                attempt++
                val message = e.message.orEmpty()
                val retryable = e is IOException ||
                    Regex("HTTP (429|5\\d\\d)", RegexOption.IGNORE_CASE).containsMatchIn(message)
                if (!retryable || attempt >= 3) throw e
                try {
                    Thread.sleep(700L * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("تم إيقاف انتظار إعادة محاولة الترجمة", interrupted)
                }
            }
        }
    }

    private fun translateTextOnce(text: String, sourceLang: String, targetLang: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val pair = URLEncoder.encode("$sourceLang|$targetLang", "UTF-8")
        val connection = (
            URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair")
                .openConnection() as HttpURLConnection
        ).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SariSubtitle/0.3")
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) throw IllegalStateException("لم يصل رد من خدمة الترجمة")

            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("خدمة الترجمة أعادت HTTP $code")

            val json = JSONObject(body)
            val status = json.optInt("responseStatus", 200)
            if (status != 200) {
                throw IllegalStateException(
                    json.optString("responseDetails", "فشلت الترجمة ($status)")
                )
            }
            val translated = json.optJSONObject("responseData")
                ?.optString("translatedText")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("لم تُرجع خدمة الترجمة نصاً")

            return Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString()
        } finally {
            connection.disconnect()
        }
    }
}
