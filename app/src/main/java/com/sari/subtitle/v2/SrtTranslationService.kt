package com.sari.subtitle.v2

import android.text.Html
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ترجمة SRT من داخل التطبيق (بدون SubWhisper).
 * ملاحظة: الترجمة تستخدم واجهة MyMemory العامة عبر الإنترنت؛ ليست محركاً محلياً
 * ولا نضمن حصتها المجانية أو توفرها الدائم. لا يتم إرسال توقيتات SRT إلى الخدمة.
 */
object SrtTranslationService {
    private val timestampPattern = Regex("\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")

    fun translateSrt(source: String, sourceLang: String, targetLang: String,
                     onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        require(sourceLang.isNotBlank() && targetLang.isNotBlank()) { "اختر لغة المصدر واللغة المستهدفة" }
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
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
            val translated = cache[text] ?: translateText(text, sourceLang, targetLang).also { cache[text] = it }
            while (lines.size > timeIndex + 1) lines.removeAt(lines.lastIndex)
            lines.add(translated)
            output.add(lines.joinToString("\n"))
            completed++
            onProgress(completed, translatable)
        }
        return output.joinToString("\r\n\r\n") + "\r\n"
    }

    private fun translateText(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text
        if (text.length > 450) throw IllegalArgumentException("أحد مقاطع الترجمة طويل جداً لخدمة الترجمة المجانية؛ قصّره أو قسّمه أولاً")
        val q = URLEncoder.encode(text, "UTF-8")
        val pair = URLEncoder.encode("$sourceLang|$targetLang", "UTF-8")
        val connection = (URL("https://api.mymemory.translated.net/get?q=$q&langpair=$pair").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SariSubtitle/0.2")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                ?: throw IllegalStateException("لم يصل رد من خدمة الترجمة")
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("خدمة الترجمة أعادت HTTP $code")
            val json = JSONObject(body)
            val status = json.optInt("responseStatus", 200)
            if (status != 200) throw IllegalStateException(json.optString("responseDetails", "فشلت الترجمة ($status)"))
            val translated = json.optJSONObject("responseData")?.optString("translatedText")
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("لم تُرجع خدمة الترجمة نصاً")
            return Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString()
        } finally {
            connection.disconnect()
        }
    }
}
