package com.sari.subtitle.v2

import android.text.Html
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
        return translateSrt(source, detected, targetLang, onProgress)
    }

    fun translateSrt(
        source: String,
        sourceLang: String,
        targetLang: String,
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

        val cache = mutableMapOf<String, String>()
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
            val translated = cache[text] ?: translateText(text, sourceLang, targetLang)
                .also { cache[text] = it }
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

    private fun translateText(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text
        if (text.length > 450) {
            throw IllegalArgumentException("أحد مقاطع الترجمة طويل جداً؛ قصّره أو قسّمه")
        }

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
