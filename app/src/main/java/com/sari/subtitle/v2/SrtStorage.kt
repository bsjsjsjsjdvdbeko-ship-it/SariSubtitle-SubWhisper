package com.sari.subtitle.v2

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * إدارة تخزين ملفات SRT محلياً، مع فهرس URL → ملف.
 *
 * المسار: /sdcard/Download/SariSubtitle/
 * الملفات:
 *   index.json    — فهرس { url_hash: { url, fileName, cueCount, savedAt } }
 *   <hash>.srt    — محتوى SRT
 */
class SrtStorage(private val context: Context) {

    private val dir: File by lazy {
        // نستخدم مجلد التطبيق الخاص (لا يحتاج صلاحيات)
        val d = File(context.filesDir, "srt")
        if (!d.exists()) d.mkdirs()
        d
    }

    private val indexFile: File by lazy { File(dir, "index.json") }

    /**
     * يحفظ SRT مرتبطاً بـ URL.
     * @return اسم الملف المحفوظ، أو null عند الفشل.
     */
    fun save(url: String, srtText: String, cueCount: Int): String? {
        return try {
            val hash = hashUrl(url)
            val fileName = "$hash.srt"
            val file = File(dir, fileName)
            file.writeText(srtText, Charsets.UTF_8)

            // تحديث الفهرس
            val index = readIndex()
            index.put(hash, JSONObject().apply {
                put("url", url)
                put("fileName", fileName)
                put("cueCount", cueCount)
                put("savedAt", System.currentTimeMillis())
            })
            writeIndex(index)
            fileName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يحمّل SRT مرتبطاً بـ URL.
     * @return نص SRT، أو null إن لم يوجد.
     */
    fun load(url: String): String? {
        return try {
            val hash = hashUrl(url)
            val fileName = readIndex().optJSONObject(hash)?.optString("fileName") ?: return null
            val file = File(dir, fileName)
            if (!file.exists()) return null
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * هل يوجد SRT محفوظ لهذا URL؟
     */
    fun has(url: String): Boolean {
        val hash = hashUrl(url)
        val fileName = readIndex().optJSONObject(hash)?.optString("fileName") ?: return false
        return File(dir, fileName).exists()
    }

    /**
     * يحذف SRT مرتبطاً بـ URL.
     */
    fun delete(url: String): Boolean {
        return try {
            val hash = hashUrl(url)
            val index = readIndex()
            val entry = index.optJSONObject(hash) ?: return false
            val fileName = entry.optString("fileName")
            File(dir, fileName).delete()
            index.remove(hash)
            writeIndex(index)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * عدد الملفات المحفوظة.
     */
    fun count(): Int {
        return try {
            readIndex().length()
        } catch (e: Exception) { 0 }
    }

    /**
     * يعيد قائمة URL المحفوظة.
     */
    fun listUrls(): List<String> {
        return try {
            val index = readIndex()
            val result = mutableListOf<String>()
            val keys = index.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val url = index.optJSONObject(k)?.optString("url") ?: continue
                result.add(url)
            }
            result
        } catch (e: Exception) { emptyList() }
    }

    private fun hashUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    private fun readIndex(): JSONObject {
        return try {
            if (indexFile.exists()) {
                JSONObject(indexFile.readText(Charsets.UTF_8))
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeIndex(index: JSONObject) {
        try {
            indexFile.writeText(index.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {}
    }
}
