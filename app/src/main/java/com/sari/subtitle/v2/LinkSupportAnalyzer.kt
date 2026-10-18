package com.sari.subtitle.v2

import android.net.Uri
import java.util.Locale

/**
 * Classifies common video/subtitle links and gives an honest next-step recommendation.
 * It does not download or extract media from social pages by itself.
 */
data class LinkSupportResult(
    val title: String,
    val details: String,
    val shortStatus: String,
    val normalizedUrl: String = ""
)

object LinkSupportAnalyzer {
    private val mediaExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")
    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml")

    fun analyze(raw: String): LinkSupportResult {
        val candidate = raw.trim().trimEnd('.', ',', ')', ']', '}', '،')
        val uri = try { Uri.parse(candidate) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (candidate.isBlank() || uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            return LinkSupportResult(
                "الرابط غير صالح",
                "أدخل رابطاً كاملاً يبدأ بـ https:// أو http:// ثم أعد الفحص.",
                "لا يوجد رابط صالح للفحص"
            )
        }

        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val extension = path.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val normalized = uri.toString()

        if (extension != null && extension in subtitleExtensions) {
            return LinkSupportResult(
                "رابط ملف ترجمة مباشر",
                "يبدو أن الرابط يشير إلى ملف ترجمة .$extension. استخدم «استيراد ملف SRT من رابط مباشر» إذا كان الملف بصيغة SRT. صيغ VTT/ASS وغيرها قد تحتاج إلى تحويل قبل الاستيراد.",
                "رابط ترجمة مباشر: .$extension",
                normalized
            )
        }
        if (extension != null && extension in mediaExtensions) {
            return LinkSupportResult(
                "رابط وسائط مباشر محتمل",
                "امتداد الرابط .$extension يشير إلى صوت/فيديو. يمكن تجربة «رابط مباشر للصوت/الفيديو ← إنشاء SRT». قد يظل التنزيل يفشل إذا كان الخادم يتطلب تسجيل دخول أو يعيد صفحة ويب، والحد الحالي 24 MB.",
                "رابط وسائط محتمل: جرّب التفريغ المباشر",
                normalized
            )
        }

        val platform = when {
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> "X / Twitter"
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "YouTube"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> "Facebook"
            host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it" -> "Reddit"
            host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
            else -> "منصة أو موقع آخر"
        }

        val details = when (platform) {
            "X / Twitter" -> "هذا رابط صفحة X وليس رابط ملف صوت مباشر. يمكن تجربة زر «تفريغ X تلقائياً» داخل WebView، لكنه تجريبي وقد يفشل إذا لم يكشف الموقع الصوت أو منع المتصفح التقاطه. البديل: رابط وسائط مباشر يمكن الوصول إليه أو ملف صوت/فيديو محلي."
            "YouTube" -> "هذا رابط صفحة YouTube. لا يستخرج التطبيق الصوت من صفحة YouTube تلقائياً في هذه النسخة. استخدم ملفاً تملك حق الوصول إليه أو رابط وسائط مباشر، أو استورد SRT موجوداً."
            "TikTok", "Instagram", "Facebook", "Reddit", "Vimeo" -> "تم التعرف على $platform. افتح الفيديو وشغّله داخل المتصفح المدمج، ثم جرّب زر «تفريغ صوت الفيديو الحالي». يعتمد النجاح على إتاحة المنصة لمسار الصوت عبر WebView؛ بعض الصفحات تستخدم DRM أو تمنع الالتقاط. إذا فشل، استخدم رابط وسائط مباشر أو ملفاً محلياً أو SRT جاهزاً."
            else -> "الرابط لا يبدو ملف وسائط مباشر حسب امتداده. قد تكون صفحة ويب أو رابطاً يحتاج إلى تسجيل دخول. جرّب فتحه في المتصفح الداخلي، أو استخدم رابط ملف صوت/فيديو مباشر أو ملفاً محلياً."
        }
        return LinkSupportResult(
            "نوع الرابط: $platform",
            "$details\n\nالمسار المجاني الأقل مخاطرة بالبيانات: الحصول على SRT مرة واحدة، حفظه محلياً، ثم إعادة استخدامه. تشغيل الفيديو نفسه عبر الإنترنت سيظل يستهلك بيانات الفيديو.",
            "تم التعرف على رابط $platform؛ استخراج الصوت من الصفحة غير مضمون",
            normalized
        )
    }
}
