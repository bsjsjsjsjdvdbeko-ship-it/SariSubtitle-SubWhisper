package com.sari.subtitle.v2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException

/**
 * Sari Subtitle — المرحلة 3.3
 * - حفظ SRT تلقائياً بعد تحميله (مربوط بـ URL).
 * - استرجاع SRT عند فتح نفس URL.
 * - عرض عدد الملفات المحفوظة.
 */
class SariSubtitleActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_AUDIO = 1004
        private const val REQ_SAVE_WHISPER_SRT = 1005
        private const val REQ_SUBWHISPER_INTERNAL = 1006
        private const val REQ_PICK_SRT = 1001
        private const val REQ_PICK_SRT_TO_TRANSLATE = 1002
        private const val REQ_TRANSLATE_SRT_PICK = 1002
        private const val REQ_SAVE_TRANSLATED_SRT = 1003
        private const val OFFSET_STEP_MS = 500L
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var subtitleOverlay: SubtitleOverlay

    private lateinit var settings: SubtitleSettings
    private var pendingWhisperSrt: String? = null
    private lateinit var storage: SrtStorage

    private val handler = Handler(Looper.getMainLooper())
    private val repo = SubtitleRepository()
    private var polling = false
    private var overlayOn = false

    /** آخر SRT نصي محمّل (للحفظ عند الحاجة) */
    private var lastSrtText: String? = null

    /** URL الذي تم تحميل SRT من أجله */
    private var lastSrtUrl: String? = null
    private var pendingSourceLang = "en"
    private var pendingTargetLang = "ar"
    private var pendingTranslatedSrt: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SubtitleSettings(this)
        storage = SrtStorage(this)
        repo.offsetMs = settings.offsetMs

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)

        subtitleOverlay.applySettings(settings)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutoShow()
                // ⚡ محاولة استرجاع SRT لهذا URL
                if (url != null) tryAutoLoadSrt(url)
            }
        }
        webView.webChromeClient = WebChromeClient()

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) webView.loadUrl(url)
        }

        findViewById<Button>(R.id.generateSrtFromLinkButton).setOnClickListener {
            openSrtGenerator()
        }

        findViewById<Button>(R.id.importSrtFromUrlButton).setOnClickListener {
            promptImportSrtFromUrl()
        }

        findViewById<Button>(R.id.transcribeDirectMediaUrlButton).setOnClickListener {
            promptTranscribeDirectMediaUrl()
        }

        findViewById<Button>(R.id.analyzeLinkButton).setOnClickListener {
            analyzeCurrentLink()
        }


        findViewById<Button>(R.id.transcribeAudioButton).setOnClickListener {
            openAudioPicker()
        }

        findViewById<Button>(R.id.transcribeXAutoButton).setOnClickListener {
            transcribeCurrentWebVideo()
        }

        findViewById<Button>(R.id.pasteLinkTranscribeButton).setOnClickListener {
            openPasteLinkFlow()
        }

        // نسخ الرابط فقط داخل Sari Subtitle؛ لا يفتح SubWhisper أو أي تطبيق خارجي.
        findViewById<Button>(R.id.copyLinkOnlyButton).setOnClickListener {
            val link = urlInput.text.toString().trim()
                .ifBlank { webView.url.orEmpty().trim() }
            val uri = try { Uri.parse(link) } catch (_: Exception) { null }
            if (link.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                Toast.makeText(this, "أدخل رابط فيديو صحيحاً أولاً", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", link))
                Toast.makeText(this, "تم نسخ الرابط فقط — لم يتم فتح أي تطبيق خارجي", Toast.LENGTH_SHORT).show()
                statusText.text = "تم نسخ الرابط إلى الحافظة. لم يتم فتح خدمة خارجية."
            }
        }

        findViewById<Button>(R.id.translateSrtButton).setOnClickListener {
            // ترجمة تلقائية: يكتشف اللغة ويفتح picker مباشرة
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType("*/*")
            }
            startActivityForResult(
                Intent.createChooser(intent, "اختر ملف SRT للترجمة التلقائية"),
                REQ_PICK_SRT_TO_TRANSLATE
            )
        }

        findViewById<Button>(R.id.reloadXButton).setOnClickListener {
            urlInput.setText("https://x.com/")
            webView.loadUrl("https://x.com/")
        }

        findViewById<Button>(R.id.checkTimeButton).setOnClickListener {
            pollCurrentTimeOnce()
        }

        findViewById<Button>(R.id.testCaptureButton).setOnClickListener {
            testCaptureStream()
        }


        // اختبار captureStream
        findViewById<Button>(R.id.checkTimeButton).setOnLongClickListener {
            testCaptureStream()
            true
        }

        findViewById<Button>(R.id.toggleOverlayButton).setOnClickListener {
            toggleOverlay()
        }

        findViewById<Button>(R.id.loadSrtButton).setOnClickListener {
            openSrtPicker()
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            SettingsDialog.show(this, settings, subtitleOverlay) {
                Toast.makeText(this, "✅ تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.offsetMinusButton).setOnClickListener {
            changeOffset(-OFFSET_STEP_MS)
        }
        findViewById<Button>(R.id.offsetPlusButton).setOnClickListener {
            changeOffset(+OFFSET_STEP_MS)
        }
        findViewById<Button>(R.id.offsetResetButton).setOnClickListener {
            repo.offsetMs = 0L
            settings.offsetMs = 0L
            Toast.makeText(this, "الإزاحة: 0.0s", Toast.LENGTH_SHORT).show()
            pollCurrentTimeOnce()
        }

        startPolling()
        val sharedUrl = extractSharedUrl(intent)
        if (sharedUrl != null) {
            urlInput.setText(sharedUrl)
            webView.loadUrl(sharedUrl)
            statusText.text = "تم استلام رابط مشاركته مع Sari Subtitle. اضغط «إنشاء SRT من رابط» لإنشاء الترجمة العربية."
        } else {
            webView.loadUrl("https://x.com/")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUrl = extractSharedUrl(intent) ?: return
        urlInput.setText(sharedUrl)
        webView.loadUrl(sharedUrl)
        statusText.text = "تم استلام الرابط المشترك. اضغط «إنشاء SRT من رابط» للمتابعة."
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() ?: return null
        val match = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(shared)
        val candidate = match?.value?.trimEnd('.', ',', ')', ']', '}', '،') ?: return null
        val uri = try { Uri.parse(candidate) } catch (_: Exception) { return null }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return candidate
    }

    /**
     * عند فتح URL جديد — إن كان له SRT محفوظ، حمّله تلقائياً.
     */
    private fun tryAutoLoadSrt(url: String) {
        if (lastSrtUrl == url) return
        if (!storage.has(url)) return
        try {
            val srtText = storage.load(url) ?: return
            val cues = SrtParser.parse(srtText.byteInputStream())
            if (cues.isEmpty()) return
            repo.setCues(cues)
            lastSrtText = srtText
            lastSrtUrl = url
            if (!overlayOn) overlayOn = true
            runOnUiThread {
                Toast.makeText(this, "✅ SRT محفوظ: ${cues.size} سطر", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // تجاهل
        }
    }

    /**
     * تعديل الإزاحة.
     */
    private fun changeOffset(deltaMs: Long) {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل SRT أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val newOffset = (repo.offsetMs + deltaMs).coerceIn(-30000L, 30000L)
        repo.offsetMs = newOffset
        settings.offsetMs = newOffset

        val sign = if (newOffset >= 0) "+" else ""
        val secStr = "%.1f".format(newOffset / 1000.0)
        Toast.makeText(this, "الإزاحة: $sign${secStr}s", Toast.LENGTH_SHORT).show()
        pollCurrentTimeOnce()
    }

    /** تحليل الرابط قبل محاولة معالجته، لتوضيح ما يمكن فعله فعلياً دون افتراض أن كل منصة قابلة للاستخراج. */
    private fun analyzeCurrentLink() {
        val raw = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty().trim() }
        val result = LinkSupportAnalyzer.analyze(raw)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(result.title)
            .setMessage(result.details)
            .setPositiveButton("نسخ الرابط") { _, _ ->
                if (result.normalizedUrl.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", result.normalizedUrl))
                    statusText.text = "تم نسخ الرابط. ${result.shortStatus}"
                    Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("فتح الرابط") { _, _ ->
                if (result.normalizedUrl.isNotBlank()) {
                    urlInput.setText(result.normalizedUrl)
                    webView.loadUrl(result.normalizedUrl)
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
        statusText.text = result.shortStatus
    }

    /**
     * ينزّل ملف صوت/فيديو من رابط مباشر فقط ثم يرسله إلى مزود STT المختار لإنشاء SRT.
     * لا يحاول استخراج روابط الوسائط من صفحات X/YouTube؛ هذه تحتاج مسار استخراج منفصلاً.
     */
    private fun promptTranscribeDirectMediaUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4 أو audio.mp3"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(urlInput.text.toString().takeIf { it.contains(".") && !it.contains("x.com") }.orEmpty())
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@SariSubtitleActivity).apply {
                text = "يعمل فقط مع رابط مباشر لملف صوت/فيديو يمكن تنزيله دون تسجيل دخول. لا يعمل مع رابط صفحة X أو YouTube العادي. حد التنزيل 24 MB، وبعده يُرسل الملف إلى مزود STT المختار؛ قد تُحسب تكلفة/حصة API وفق حسابك."
                setPadding(0, 12, 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إنشاء SRT من ملف وسائط مباشر")
            .setView(wrap)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تنزيل وتفريغ") { _, _ ->
                val link = input.text.toString().trim()
                val uri = try { Uri.parse(link) } catch (_: Exception) { null }
                if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "أدخل رابط HTTP/HTTPS صالحاً", Toast.LENGTH_LONG).show()
                } else if (!hasSelectedSttKey()) {
                    Toast.makeText(this, "أدخل مفتاح ${settings.sttProviderLabel} من الإعدادات أولاً", Toast.LENGTH_LONG).show()
                } else {
                    chooseDirectMediaLanguage(link)
                }
            }
            .show()
    }

    private fun chooseDirectMediaLanguage(link: String) {
        val labels = arrayOf("اكتشاف اللغة تلقائياً", "العربية", "الإنجليزية")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("لغة الصوت")
            .setItems(labels) { _, selected ->
                val lang = when (selected) { 1 -> "ar"; 2 -> "en"; else -> "auto" }
                downloadAndTranscribeDirectMedia(link, lang)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun downloadAndTranscribeDirectMedia(link: String, language: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("إنشاء ملف SRT")
            setMessage("جارٍ تنزيل ملف الوسائط…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        statusText.text = "جارٍ تنزيل ملف الوسائط من الرابط المباشر…"
        Thread {
            var temp: java.io.File? = null
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(link).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("فشل تنزيل الملف (HTTP $code). قد يكون الرابط صفحة ويب وليس ملف وسائط مباشراً.")
                val type = connection.contentType.orEmpty().lowercase(java.util.Locale.ROOT)
                if (type.contains("text/html") || type.contains("application/json")) {
                    throw IOException("الرابط أعاد صفحة ويب/JSON وليس ملف وسائط مباشراً. روابط صفحات X وYouTube لا تعمل بهذا المسار.")
                }
                val declared = connection.contentLengthLong
                if (declared > 24L * 1024L * 1024L) throw IOException("حجم الملف يتجاوز 24 MB. اختر ملفاً أصغر لتجنب استهلاك البيانات.")
                val path = Uri.parse(link).lastPathSegment.orEmpty()
                val ext = path.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                temp = java.io.File.createTempFile("direct_media_", ".$ext", cacheDir)
                var total = 0L
                connection.inputStream.use { inputStream -> temp!!.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 24L * 1024L * 1024L) throw IOException("توقف التنزيل لأن الملف تجاوز 24 MB.")
                        output.write(buffer, 0, count)
                    }
                } }
                if (total == 0L) throw IOException("الملف الذي تم تنزيله فارغ.")
                runOnUiThread { progress.setMessage("تم تنزيل ${(total / 1024)} KB — جارٍ التفريغ عبر ${settings.sttProviderLabel}…") }
                val result = transcribeWithSelectedProvider(Uri.fromFile(temp!!), language)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.dismiss()
                    pendingWhisperSrt = result.srt
                    statusText.text = "اكتمل التفريغ: ${result.segmentCount} مقطع"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تم إنشاء SRT")
                        .setMessage("تم استخراج ${result.segmentCount} مقطعاً. احفظ الملف لاستخدامه لاحقاً.")
                        .setNegativeButton("إغلاق", null)
                        .setPositiveButton("حفظ SRT") { _, _ ->
                            val saveIntent1 = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                setType("application/x-subrip")
                                putExtra(Intent.EXTRA_TITLE, "transcription.srt")
                            }
                            startActivityForResult(saveIntent1, REQ_SAVE_WHISPER_SRT)
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        statusText.text = "تعذر إنشاء SRT من الرابط المباشر"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذر إنشاء SRT")
                            .setMessage(e.message ?: "خطأ غير معروف")
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
                temp?.delete()
            }
        }.start()
    }

    /** يتيح جلب ملف ترجمة SRT منشور برابط مباشر؛ لا يستخرج الصوت من رابط الفيديو. */
    private fun promptImportSrtFromUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/subtitles.srt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(urlInput.text.toString().takeIf { it.lowercase().endsWith(".srt") }.orEmpty())
            setSelection(text.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("استيراد SRT من رابط مباشر")
            .setMessage("أدخل رابط ملف ترجمة SRT مباشر. هذا يستورد ملف ترجمة موجوداً ولا ينشئ ترجمة من رابط الفيديو.")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تحميل الملف") { _, _ ->
                val subtitleUrl = input.text.toString().trim()
                val uri = try { Uri.parse(subtitleUrl) } catch (_: Exception) { null }
                if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "أدخل رابط SRT صالحاً يبدأ بـ https:// أو http://", Toast.LENGTH_LONG).show()
                } else {
                    downloadSrtFromUrl(subtitleUrl)
                }
            }
            .show()
    }

    private fun downloadSrtFromUrl(subtitleUrl: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("تحميل ملف SRT")
            setMessage("جارٍ تنزيل ملف الترجمة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(true)
            show()
        }
        val worker = Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(subtitleUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 25000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "text/plain, application/x-subrip, */*")
                    setRequestProperty("User-Agent", "SariSubtitle/0.4 Android")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("تعذر تحميل الملف (HTTP $code)")
                val length = connection.contentLengthLong
                if (length > 5L * 1024L * 1024L) throw IOException("حجم ملف SRT أكبر من الحد المسموح (5 MB)")
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("تم إلغاء التحميل")
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 5L * 1024L * 1024L) throw IOException("حجم ملف SRT أكبر من الحد المسموح (5 MB)")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                val cues = SrtParser.parse(bytes.inputStream())
                if (cues.isEmpty()) throw IOException("الرابط لا يحتوي على مقاطع SRT صالحة. تأكد أنه رابط ملف مباشر وليس صفحة ويب.")
                runOnUiThread {
                    if (!isFinishing) {
                        repo.setCues(cues)
                        lastSrtText = text
                        val videoUrl = webView.url ?: urlInput.text.toString().trim()
                        if (videoUrl.startsWith("http")) {
                            storage.save(videoUrl, text, cues.size)
                            lastSrtUrl = videoUrl
                        }
                        overlayOn = true
                        statusText.text = "تم تحميل SRT من رابط مباشر: ${cues.size} مقطع${if (videoUrl.startsWith("http")) " — حُفظ للفيديو الحالي" else ""}"
                        Toast.makeText(this, "تم استيراد ${cues.size} مقطع SRT", Toast.LENGTH_LONG).show()
                    }
                    progress.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذر تحميل SRT")
                            .setMessage(e.message ?: "خطأ غير معروف")
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
        progress.setOnCancelListener { worker.interrupt() }
        worker.start()
    }

    /**
     * Generates SRT using a real provider-specific path where possible.
     * Public YouTube captions are fetched directly (no video/audio download); other platforms
     * continue through the explicit supported paths instead of pretending universal extraction.
     */
    private fun openSrtGenerator() {
        val videoUrl = (urlInput.text.toString().trim().ifBlank { webView.url ?: "" }).trim()
        val uri = try { Uri.parse(videoUrl) } catch (_: Exception) { null }
        if (videoUrl.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "ألصق رابط فيديو صحيحاً أولاً", Toast.LENGTH_LONG).show()
            urlInput.requestFocus()
            return
        }

        val youtubeId = YouTubeCaptionExtractor.getVideoId(videoUrl)
        if (youtubeId != null) {
            promptYouTubeCaptionExtraction(videoUrl)
            return
        }

        if (settings.processingEngine == SubtitleSettings.ENGINE_INTERNAL) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("محرك Sari Subtitle الداخلي")
                .setMessage("تم اختيار المحرك الداخلي. إنشاء SRT من رابط الفيديو لم يُنفّذ بعد: لا يوجد حالياً خادم استخراج صوت وتفريغ كلام مهيأ لهذا المسار. لن نفتح SubWhisper تلقائياً ولن ندّعي نجاح العملية. يمكنك حالياً استخدام زر «ترجمة ملف SRT» لترجمة ملف موجود.")
                .setPositiveButton("حسنًا", null)
                .show()
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", videoUrl))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("إنشاء SRT عبر SubWhisper")
                .setMessage("نُسخ رابط الفيديو. سيفتح الموقع الخارجي؛ الصق الرابط هناك، واختر اللغة المستهدفة، ثم نزّل SRT وارجع إلى التطبيق لاستيراده. قد لا تدعم الخدمة بعض الروابط أو قد تفرض حدوداً مجانية. هذا المسار خارجي وليس محركاً مدمجاً.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة داخل التطبيق") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sub.kuandre.com/")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "تعذّر فتح الموقع: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تجهيز الرابط: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Extracts an existing public YouTube caption track without downloading the video. */
    private fun promptYouTubeCaptionExtraction(videoUrl: String) {
        val labels = arrayOf("أفضل مسار متاح تلقائياً", "العربية", "الإنجليزية")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إنشاء SRT من ترجمة YouTube المتاحة")
            .setMessage("سيحاول التطبيق جلب الترجمة/التفريغ النصي الذي يكشفه YouTube علناً، دون تنزيل الفيديو أو الصوت. قد تكون الترجمة تلقائية أو بلغة الفيديو، ولا تعمل هذه الطريقة مع الفيديوهات الخاصة أو التي لا تعرض مسارات ترجمة. إذا لم تتوفر ترجمة، يمكنك تجربة مسار ملف الوسائط المباشر أو استيراد SRT موجود.")
            .setItems(labels) { _, selected ->
                val language = when (selected) { 1 -> "ar"; 2 -> "en"; else -> "auto" }
                fetchYouTubeCaptions(videoUrl, language)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun fetchYouTubeCaptions(videoUrl: String, language: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("جلب ترجمة YouTube")
            setMessage("جارٍ فحص مسارات الترجمة العامة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        statusText.text = "جارٍ محاولة استخراج مسار ترجمة عام من YouTube دون تنزيل الفيديو…"
        Thread {
            try {
                val result = YouTubeCaptionExtractor.extract(videoUrl, language)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.dismiss()
                    pendingWhisperSrt = result.srt
                    val cues = SrtParser.parse(result.srt.byteInputStream(Charsets.UTF_8))
                    repo.setCues(cues)
                    lastSrtText = result.srt
                    lastSrtUrl = videoUrl
                    storage.save(videoUrl, result.srt, cues.size)
                    overlayOn = true
                    statusText.text = "تم جلب ${result.cueCount} مقطع من ترجمة YouTube (${result.languageCode})"
                    val generated = if (result.autoGenerated) "تلقائية" else "مضافة"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تم إنشاء ملف SRT")
                        .setMessage("تم جلب ${result.cueCount} مقطعاً باللغة ${result.languageCode}. نوع المسار: $generated. حُفظت نسخة داخل التطبيق لإعادة استخدامها. إذا أردت ترجمتها للعربية، استخدم زر ترجمة ملف SRT.")
                        .setNegativeButton("إغلاق", null)
                        .setPositiveButton("حفظ SRT") { _, _ ->
                            val saveIntent2 = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                setType("application/x-subrip")
                                putExtra(Intent.EXTRA_TITLE, "youtube-captions-${YouTubeCaptionExtractor.getVideoId(videoUrl)}.srt")
                            }
                            startActivityForResult(saveIntent2, REQ_SAVE_WHISPER_SRT)
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        statusText.text = "تعذر جلب ترجمة YouTube العامة"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("لم تتوفر ترجمة قابلة للجلب")
                            .setMessage((e.message ?: "خطأ غير معروف") + "\n\nلم يتم تنزيل الفيديو. يمكنك تجربة زر «رابط مباشر للصوت/الفيديو ← إنشاء SRT» إذا كان لديك رابط ملف مباشر يمكن الوصول إليه، أو استيراد ملف SRT جاهز. لا يضمن هذا التطبيق استخراج الصوت من كل رابط اجتماعي.")
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /**
     * مسار سريع للرابط: يقبل لصق الرابط في حوار مستقل، ثم ينسخه ويفتح SubWhisper.
     * لا ندّعي استخراج الصوت تلقائياً داخل التطبيق؛ الروابط الاجتماعية تحتاج عادةً
     * إلى خدمة استخراج خارجية، وقد تتطلب تسجيل الدخول أو لا تكون مدعومة.
     */
    private fun openPasteLinkFlow() {
        val clipboardText = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0) {
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            } else ""
        } catch (_: Exception) { "" }
        val clipboardUrl = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(clipboardText)?.value?.trimEnd('.', ',', ')', ']', '}', '،').orEmpty()

        val linkInput = EditText(this).apply {
            hint = "الصق رابط الفيديو هنا"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            val existingUrl = urlInput.text.toString().trim().takeUnless { it == "https://x.com/" }.orEmpty()
            setText(clipboardUrl.ifBlank { existingUrl })
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
            addView(linkInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(TextView(this@SariSubtitleActivity).apply {
                text = "سيُفتح موقع SubWhisper داخل Sari Subtitle دون الانتقال إلى تطبيق آخر. راجع الرابط واضغط إنشاء الترجمة في الصفحة؛ عند تنزيل SRT من الصفحة سيحاول التطبيق استيراده تلقائياً. قد لا تعمل الروابط المحمية أو غير المدعومة."
                setPadding(0, 12, 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("الصق الرابط فقط")
            .setView(container)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة داخل التطبيق") { _, _ ->
                val link = linkInput.text.toString().trim()
                val uri = try { Uri.parse(link) } catch (_: Exception) { null }
                if (link.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "ألصق رابط فيديو صحيحاً يبدأ بـ http أو https", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    urlInput.setText(link)
                    statusText.text = "تم فتح SubWhisper داخل Sari Subtitle. ألصق/راجع الرابط واضغط إنشاء الترجمة داخل الصفحة."
                    val internalIntent = Intent(this, SubWhisperInternalActivity::class.java)
                        .putExtra(SubWhisperInternalActivity.EXTRA_INITIAL_URL, link)
                    startActivityForResult(internalIntent, REQ_SUBWHISPER_INTERNAL)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذّر فتح نافذة التفريغ الداخلية: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private data class LanguageOption(val code: String, val label: String) {
        override fun toString(): String = label
    }

    private fun showSrtTranslationDialog() {
        val languages = listOf(
            LanguageOption("ar", "العربية"), LanguageOption("en", "الإنجليزية"),
            LanguageOption("es", "الإسبانية"), LanguageOption("fr", "الفرنسية"),
            LanguageOption("de", "الألمانية"), LanguageOption("tr", "التركية"),
            LanguageOption("ru", "الروسية"), LanguageOption("it", "الإيطالية"),
            LanguageOption("pt", "البرتغالية"), LanguageOption("zh-CN", "الصينية"),
            LanguageOption("ja", "اليابانية"), LanguageOption("ko", "الكورية"),
            LanguageOption("fa", "الفارسية"), LanguageOption("ur", "الأوردية"),
            LanguageOption("hi", "الهندية")
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        root.addView(TextView(this).apply { text = "لغة الملف الأصلية" })
        val sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        sourceSpinner.setSelection(languages.indexOfFirst { it.code == "en" })
        root.addView(sourceSpinner)
        root.addView(TextView(this).apply { text = "ترجمة الملف إلى"; setPadding(0, 18, 0, 0) })
        val targetSpinner = Spinner(this)
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        targetSpinner.setSelection(languages.indexOfFirst { it.code == "ar" })
        root.addView(targetSpinner)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ترجمة ملف SRT")
            .setMessage("سيتم إرسال نص المقاطع إلى خدمة ترجمة عبر الإنترنت. أرقام المقاطع وتوقيتاتها تبقى كما هي. هذه الخدمة ليست SubWhisper، لكنها تتطلب الإنترنت وقد تفرض حدود استخدام.")
            .setView(root)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("اختيار ملف SRT") { _, _ ->
                pendingSourceLang = (sourceSpinner.selectedItem as LanguageOption).code
                pendingTargetLang = (targetSpinner.selectedItem as LanguageOption).code
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("*/*")
                }
                startActivityForResult(Intent.createChooser(intent, "اختر ملف SRT لترجمته"), REQ_TRANSLATE_SRT_PICK)
            }
            .show()
    }

    private fun translateSelectedSrt(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(this).apply {
            setTitle("ترجمة ملف SRT")
            setMessage("جارٍ بدء الترجمة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val translated = SrtTranslationService.translateSrt(raw, pendingSourceLang, pendingTargetLang, applicationContext) { done, total ->
                    runOnUiThread { progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") }
                }
                pendingTranslatedSrt = translated
                runOnUiThread {
                    progress.dismiss()
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "translated_${pendingTargetLang}.srt")
                    }
                    startActivityForResult(create, REQ_SAVE_TRANSLATED_SRT)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تعذرت الترجمة")
                        .setMessage("${e.message ?: "خطأ غير معروف"}\n\nتحقق من الإنترنت ورمز اللغة وحدود الخدمة المجانية، ثم حاول مرة أخرى.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            }
        }.start()
    }

    private fun saveTranslatedSrt(uri: Uri) {
        val translated = pendingTranslatedSrt ?: run {
            Toast.makeText(this, "لا يوجد ملف مترجم للحفظ", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(translated.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("تعذّر فتح الملف للحفظ")
            pendingTranslatedSrt = null
            Toast.makeText(this, "تم حفظ ملف SRT المترجم مع الحفاظ على التوقيتات", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل حفظ الملف: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSrtPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
        }
        try {
            startActivityForResult(
                Intent.createChooser(intent, "اختر ملف SRT"),
                REQ_PICK_SRT
            )
        } catch (e: Exception) {
            Toast.makeText(this, "لا يوجد مدير ملفات", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ترجمة SRT تلقائية: يكتشف اللغة ثم يترجم إلى العربية.
     * لا حوارات — المستخدم اختار الملف فقط.
     */
    private fun processTranslateSrt(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }

        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setTitle("ترجمة SRT تلقائياً")
            setMessage("جارٍ اكتشاف اللغة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val translated = SrtTranslationService.translateAuto(
                    source = raw,
                    targetLang = "ar",
                    onDetected = { lang ->
                        runOnUiThread { progress.setMessage("🌐 اللغة المكتشفة: $lang\nجارٍ الترجمة…") }
                    },
                    context = applicationContext
                ) { done, total ->
                    runOnUiThread { progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") }
                }

                pendingTranslatedSrt = translated
                pendingTargetLang = "ar"

                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "✅ تمت الترجمة — اختر مكان الحفظ", Toast.LENGTH_SHORT).show()
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "translated_ar_${System.currentTimeMillis()}.srt")
                    }
                    startActivityForResult(create, REQ_SAVE_TRANSLATED_SRT)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تعذرت الترجمة")
                        .setMessage("${e.message ?: "خطأ غير معروف"}\n\nتحقق من الإنترنت، ثم حاول مرة أخرى.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            }
        }.start()
    }

    // ═══════════════════════════════════════════════════════════
    // تفريغ صوت عبر مزود STT المختار (Groq أو Deepgram)
    // ═══════════════════════════════════════════════════════════

        private fun testCaptureStream() {
        // فحص دعم WebViewFeature
        val supportsArrayBuffer = WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER
        )
        val supportsWebMessageListener = WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER
        )
        android.util.Log.d("SariTest", "ArrayBuffer: $supportsArrayBuffer, Listener: $supportsWebMessageListener")
        
        val js = """
            (function() {
              try {
                var v = document.querySelector('video');
                if (!v) return JSON.stringify({error: "no_video_found"});
                var r = {
                  captureStream: typeof v.captureStream,
                  MediaRecorder: typeof MediaRecorder,
                  currentTime: v.currentTime,
                  duration: v.duration,
                  paused: v.paused,
                  src: v.currentSrc ? v.currentSrc.substring(0, 50) : "none",
                  hasAudioTracks: false,
                  audioTrackCount: 0
                };
                try {
                  var s = v.captureStream();
                  if (s) {
                    var tracks = s.getAudioTracks();
                    r.audioTrackCount = tracks.length;
                    r.hasAudioTracks = tracks.length > 0;
                  }
                } catch(e) {
                  r.captureError = String(e);
                }
                return JSON.stringify(r);
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { raw ?: "null" }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("فحص captureStream + WebView")
                    .setMessage(
                        "دعم WebView:\n" +
                        "• ArrayBuffer: $supportsArrayBuffer\n" +
                        "• WebMessageListener: $supportsWebMessageListener\n\n" +
                        "captureStream:\n" +
                        decoded
                    )
                    .setPositiveButton("حسناً", null)
                    .show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // تفريغ فيديو الصفحة الحالية: captureStream + MediaRecorder + مزود STT المختار
    // يعمل فقط عندما تسمح المنصة وWebView بالتقاط مسار الصوت.
    // ═══════════════════════════════════════════════════════════
    
    private fun transcribeCurrentWebVideo() {
        Toast.makeText(this, "🔍 جارٍ فحص فيديو الصفحة الحالية…", Toast.LENGTH_SHORT).show()
        statusText.text = "🔍 فحص فيديو الصفحة الحالية…"
        
        val js = """
            (function() {
              try {
                var all = Array.from(document.querySelectorAll('video'));
                if (!all.length) return JSON.stringify({error: "no_video"});
                // Prefer a playing video, then the largest video element.
                all.sort(function(a,b) {
                  var ap = !a.paused && !a.ended ? 1 : 0;
                  var bp = !b.paused && !b.ended ? 1 : 0;
                  if (ap !== bp) return bp - ap;
                  return ((b.videoWidth||b.clientWidth)*(b.videoHeight||b.clientHeight)) - ((a.videoWidth||a.clientWidth)*(a.videoHeight||a.clientHeight));
                });
                var v = all[0];
                window.__sariSelectedVideo = v;
                return JSON.stringify({
                  hasVideo: true,
                  videoCount: all.length,
                  duration: v.duration || 0,
                  paused: v.paused,
                  canCapture: typeof v.captureStream === 'function',
                  canRecord: typeof MediaRecorder !== 'undefined'
                });
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { "null" }
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(decoded)
                    if (obj.has("error")) {
                        Toast.makeText(this, "❌ لا يوجد فيديو", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val canCapture = obj.optBoolean("canCapture", false)
                    val canRecord = obj.optBoolean("canRecord", false)
                    val duration = obj.optDouble("duration", 0.0)
                    if (!canCapture || !canRecord) {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("التقاط الصوت غير متاح")
                            .setMessage("هذه الصفحة لا تتيح التقاط صوت الفيديو عبر WebView. جرّب تشغيل الفيديو داخل الصفحة، أو استخدم رابط وسائط مباشر/ملفاً محلياً. بعض المنصات تمنع التقاط الصوت أو تستخدم DRM.")
                            .setPositiveButton("حسناً", null)
                            .show()
                        return@runOnUiThread
                    }
                    // هذا المسار تجريبي ويقيد التسجيل إلى دقيقتين لتقليل الذاكرة والرفع.
                    if (duration > 120.0) {
                        Toast.makeText(this, "سيتم التقاط أول دقيقتين فقط في هذا المسار التجريبي. للفيديو الكامل استخدم ملف صوت/فيديو محلياً.", Toast.LENGTH_LONG).show()
                    }
                    startCurrentWebVideoRecording(duration)
                } catch (e: Exception) {
                    Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun startCurrentWebVideoRecording(duration: Double) {
        val recordDurationMs = (minOf(duration * 1000.0, 120000.0)).toInt()  // 2 دقيقة كحد أقصى
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("🎙️ تسجيل صوت الفيديو الحالي")
            setMessage("جارٍ التسجيل…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        
        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timer = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val remaining = (recordDurationMs / 1000) - elapsed
                progress.setMessage("🎙️ جارٍ التسجيل…\nمضى: ${elapsed}s\nالمتبقي: ${remaining}s")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)
        
        val js = """
            (async function() {
              try {
                var v = window.__sariSelectedVideo || document.querySelector('video');
                if (!v) return JSON.stringify({error: "no_video"});
                if (v.paused) await v.play();
                await new Promise(r => setTimeout(r, 500));
                
                var stream = v.captureStream();
                var audioTracks = stream.getAudioTracks();
                if (audioTracks.length === 0) return JSON.stringify({error: "no_audio"});
                
                var audioStream = new MediaStream(audioTracks);
                var recorder = new MediaRecorder(audioStream, {
                  mimeType: 'audio/webm;codecs=opus',
                  audioBitsPerSecond: 128000
                });
                
                var chunks = [];
                recorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
                
                return new Promise((resolve) => {
                  recorder.onstop = async () => {
                    var blob = new Blob(chunks, { type: 'audio/webm' });
                    var reader = new FileReader();
                    reader.onload = () => resolve(JSON.stringify({
                      audio: reader.result.split(',')[1],
                      size: blob.size
                    }));
                    reader.readAsDataURL(blob);
                  };
                  recorder.start();
                  setTimeout(() => recorder.stop(), ${recordDurationMs});
                });
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { raw ->
            handler.removeCallbacks(timer)
            progress.dismiss()
            
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { raw ?: "null" }
            
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(decoded)
                    if (obj.has("error")) {
                        Toast.makeText(this, "❌ ${obj.getString("error")}", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val b64 = obj.getString("audio")
                    val sizeKB = obj.optInt("size", 0) / 1024
                    statusText.text = "✅ تم تسجيل $sizeKB KB — جارٍ إرسال إلى ${settings.sttProviderLabel}…"
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    sendToGroq(bytes)
                } catch (e: Exception) {
                    Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun sendToGroq(audioBytes: ByteArray) {
        if (!hasSelectedSttKey()) {
            Toast.makeText(this, "أدخل مفتاح ${settings.sttProviderLabel} من الإعدادات أولاً", Toast.LENGTH_LONG).show()
            return
        }
        
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("🌐 ${settings.sttProviderLabel}")
            setMessage("جارٍ التفريغ…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        
        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timer = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                progress.setMessage("🌐 جارٍ التفريغ…\nمضى: ${elapsed}s")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)
        
        Thread {
            try {
                val tempFile = java.io.File(cacheDir, "x_audio_${System.currentTimeMillis()}.webm")
                tempFile.writeBytes(audioBytes)
                
                val result = try {
                    transcribeWithSelectedProvider(android.net.Uri.fromFile(tempFile), "auto")
                } finally {
                    // Always remove the temporary recording, including on API errors.
                    tempFile.delete()
                }
                
                handler.removeCallbacks(timer)
                runOnUiThread {
                    progress.dismiss()
                    try {
                        val cues = SrtParser.parse(result.srt.byteInputStream())
                        if (cues.isEmpty()) {
                            Toast.makeText(this, "لم يتم استخراج مقاطع", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        repo.setCues(cues)
                        overlayOn = true
                        statusText.text = "✅ SRT جاهز: ${cues.size} مقطع"
                        Toast.makeText(this, "✅ تم التفريغ: ${cues.size} مقطع SRT", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "خطأ في SRT: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                handler.removeCallbacks(timer)
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "❌ فشل التفريغ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

private data class SelectedTranscriptionResult(val language: String, val text: String, val srt: String, val segmentCount: Int)

    private fun hasSelectedSttKey(): Boolean = when (settings.sttProvider) {
        SubtitleSettings.STT_DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
        SubtitleSettings.STT_OPENAI -> settings.openAiApiKey.isNotBlank()
        else -> settings.groqApiKey.isNotBlank()
    }

    private fun transcribeWithSelectedProvider(uri: Uri, language: String): SelectedTranscriptionResult {
        return when (settings.sttProvider) {
            SubtitleSettings.STT_DEEPGRAM -> {
                val result = DeepgramTranscriptionService(applicationContext) { settings.deepgramApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
            SubtitleSettings.STT_OPENAI -> {
                val result = OpenAIWhisperService(applicationContext) { settings.openAiApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
            else -> {
                val result = GroqWhisperService(applicationContext) { settings.groqApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("audio/*")
        }
        try {
            startActivityForResult(intent, REQ_PICK_AUDIO)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح مدير الملفات", Toast.LENGTH_LONG).show()
        }
    }

    private fun transcribeAudioFile(uri: Uri) {
        val labels = arrayOf("العربية (ar)", "الإنجليزية (en)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("لغة الصوت")
            .setItems(labels) { _, selected ->
                val lang = if (selected == 0) "ar" else "en"
                runGroqTranscription(uri, lang)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runGroqTranscription(uri: Uri, language: String) {
        if (!hasSelectedSttKey()) {
            Toast.makeText(this, "أدخل مفتاح ${settings.sttProviderLabel} من الإعدادات أولاً", Toast.LENGTH_LONG).show()
            return
        }

        // إنشاء ProgressDialog مع مؤقت
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("تفريغ الصوت (${settings.sttProviderLabel})")
            setMessage("جارٍ بدء الطلب…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(
                android.content.DialogInterface.BUTTON_NEGATIVE,
                "إلغاء"
            ) { _, _ -> /* يمكن إلغاؤه لاحقاً */ }
            show()
        }

        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var cancelled = false

        // مؤقت يحدّث كل ثانية
        val timer = object : Runnable {
            override fun run() {
                if (cancelled) return
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val mins = elapsed / 60
                val secs = elapsed % 60
                progress.setMessage("جارٍ إرسال الملف إلى ${settings.sttProviderLabel}…\nمضى: ${mins}:${String.format("%02d", secs)}")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)

        statusText.text = "⏳ جارٍ التفريغ… (${language})"

        Thread {
            try {
                val result = transcribeWithSelectedProvider(uri, language)
                val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                cancelled = true
                runOnUiThread {
                    handler.removeCallbacks(timer)
                    progress.dismiss()
                    pendingWhisperSrt = result.srt
                    statusText.text = "✅ اكتمل التفريغ: ${result.segmentCount} مقطع (${totalTime}s)"
                    Toast.makeText(this, "✅ اكتمل التفريغ بعد ${totalTime} ثانية", Toast.LENGTH_LONG).show()
                    val saveIntent3 = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "transcription.srt")
                    }
                    startActivityForResult(saveIntent3, REQ_SAVE_WHISPER_SRT)
                }
            } catch (e: Exception) {
                cancelled = true
                val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                runOnUiThread {
                    handler.removeCallbacks(timer)
                    progress.dismiss()
                    val msg = e.message ?: "خطأ غير معروف"
                    statusText.text = "❌ فشل التفريغ بعد ${totalTime}s: $msg"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("فشل التفريغ")
                        .setMessage("$msg\n\n(بعد ${totalTime} ثانية)")
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
        }.start()
    }

    private fun saveWhisperSrt(uri: Uri) {
        val srt = pendingWhisperSrt
        if (srt.isNullOrBlank()) {
            Toast.makeText(this, "لا يوجد SRT لحفظه", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(srt.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("تعذر فتح ملف الحفظ")
            pendingWhisperSrt = null
            statusText.text = "تم حفظ SRT"
            Toast.makeText(this, "تم حفظ transcription.srt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == REQ_SUBWHISPER_INTERNAL) {
            var srtText = data?.getStringExtra(SubWhisperInternalActivity.EXTRA_SRT_TEXT)
            // إذا لم يصل، اقرأ من SrtStorage (احتياطي)
            if (srtText.isNullOrBlank()) {
                srtText = try {
                    storage.load("subwhisper-latest")
                } catch (e: Exception) { null }
                android.util.Log.d("SariSrt", "read from storage: ${srtText?.length ?: 0} chars")
            }
            if (srtText.isNullOrBlank()) {
                Toast.makeText(this, "لم يصل ملف SRT من صفحة SubWhisper", Toast.LENGTH_LONG).show()
                return
            }
            try {
                val cues = SrtParser.parse(srtText.byteInputStream())
                if (cues.isEmpty()) {
                    Toast.makeText(this, "الملف المستلم لا يحتوي على مقاطع SRT صالحة", Toast.LENGTH_LONG).show()
                    return
                }
                repo.setCues(cues)
                lastSrtText = srtText
                val currentUrl = urlInput.text.toString().trim()
                if (currentUrl.startsWith("http")) {
                    storage.save(currentUrl, srtText, cues.size)
                    lastSrtUrl = currentUrl
                }
                overlayOn = true
                statusText.text = "تم استيراد SRT من SubWhisper داخل التطبيق: ${cues.size} مقطع"
                Toast.makeText(this, "تم استيراد ${cues.size} مقطع SRT", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر قراءة SRT: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK_SRT -> loadSrtFromUri(uri)
            REQ_PICK_SRT_TO_TRANSLATE -> processTranslateSrt(uri)
            REQ_TRANSLATE_SRT_PICK -> translateSelectedSrt(uri)
            REQ_SAVE_TRANSLATED_SRT -> saveTranslatedSrt(uri)
            REQ_PICK_AUDIO -> transcribeAudioFile(uri)
            REQ_SAVE_WHISPER_SRT -> saveWhisperSrt(uri)
        }
    }

    private fun loadSrtFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "تعذّر فتح الملف", Toast.LENGTH_SHORT).show()
                return
            }
            val srtText = input.use { it.readBytes().toString(Charsets.UTF_8) }
            val cues = SrtParser.parse(srtText.byteInputStream())
            if (cues.isEmpty()) {
                Toast.makeText(this, "لا ترجمات في الملف", Toast.LENGTH_LONG).show()
                return
            }
            repo.setCues(cues)
            lastSrtText = srtText
            if (!overlayOn) overlayOn = true

            // ⚡ احفظ مرتبطاً بـ URL الحالي
            val currentUrl = webView.url ?: urlInput.text.toString().trim()
            if (currentUrl.isNotEmpty() && currentUrl.startsWith("http")) {
                storage.save(currentUrl, srtText, cues.size)
                lastSrtUrl = currentUrl
                Toast.makeText(this, "✅ تم تحميل ${cues.size} سطر + حفظ", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "✅ تم تحميل ${cues.size} سطر", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleOverlay() {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل SRT أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        overlayOn = !overlayOn
        Toast.makeText(
            this,
            if (overlayOn) "✅ الترجمة مفعّلة" else "❌ معطّلة",
            Toast.LENGTH_SHORT
        ).show()
        if (!overlayOn) subtitleOverlay.hide()
    }

    private fun injectAutoShow() {
        val js = """
            (function() {
              if (window.__sariAutoShowInterval) {
                clearInterval(window.__sariAutoShowInterval);
              }
              var keywords = ['Show','عرض','إظهار','اظهار','Mostrar','Afficher'];
              function isShow(el) {
                if (!el) return false;
                var t = (el.innerText || el.textContent || '').trim();
                if (!t || t.length > 30) return false;
                var l = t.toLowerCase();
                for (var i = 0; i < keywords.length; i++) {
                  if (l === keywords[i].toLowerCase()) return true;
                }
                return false;
              }
              function scan() {
                try {
                  var els = document.querySelectorAll('div[role="button"], button, span[role="button"]');
                  for (var i = 0; i < els.length; i++) {
                    if (isShow(els[i])) { try { els[i].click(); } catch(e){} }
                  }
                } catch(e){}
              }
              window.__sariAutoShowInterval = setInterval(scan, 1500);
              scan();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                pollCurrentTimeOnce()
                handler.postDelayed(this, 250L)
            }
        })
    }

    private fun pollCurrentTimeOnce() {
        val js = """
            (function() {
              try {
                function collect(root, acc) {
                  try {
                    var v = root.querySelectorAll('video');
                    for (var i = 0; i < v.length; i++) acc.push(v[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                      if (all[j].shadowRoot) collect(all[j].shadowRoot, acc);
                    }
                  } catch(e){}
                  return acc;
                }
                var vids = collect(document, []);
                if (vids.length === 0) return JSON.stringify({totalVideos: 0});
                var best = null, maxArea = 0;
                for (var i = 0; i < vids.length; i++) {
                  try {
                    var v = vids[i];
                    var r = v.getBoundingClientRect();
                    if (r.width < 100 || r.height < 100) continue;
                    if (r.bottom < 0 || r.top > window.innerHeight) continue;
                    var vTop = Math.max(0, r.top);
                    var vBot = Math.min(window.innerHeight, r.bottom);
                    var vH = Math.max(0, vBot - vTop);
                    var area = r.width * vH;
                    if (area > maxArea) { maxArea = area; best = v; }
                  } catch(e){}
                }
                if (!best) best = vids[0];
                var t = Number(best.currentTime);
                var d = Number(best.duration);
                return JSON.stringify({
                  totalVideos: vids.length,
                  currentTime: Number.isFinite(t) ? t : 0,
                  duration: Number.isFinite(d) ? d : 0,
                  paused: !!best.paused,
                  readyState: best.readyState
                });
              } catch(e) { return JSON.stringify({error: String(e)}); }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { "null" }
            runOnUiThread { onTimeUpdate(decoded) }
        }
    }

    private fun onTimeUpdate(json: String) {
        try {
            val o = JSONObject(json)
            if (o.has("error")) {
                statusText.text = "خطأ: ${o.getString("error")}"
                return
            }
            val total = o.optInt("totalVideos", 0)
            if (total == 0) {
                statusText.text = "لا يوجد فيديو — total=0"
                return
            }
            val t = o.optDouble("currentTime", 0.0)
            val d = o.optDouble("duration", 0.0)
            val p = o.optBoolean("paused", false)
            val r = o.optInt("readyState", 0)

            val cue = repo.findCueAt((t * 1000).toLong())

            if (overlayOn && !repo.isEmpty) {
                if (cue != null) subtitleOverlay.show(cue.text)
                else subtitleOverlay.hide()
            } else if (!overlayOn) {
                subtitleOverlay.hide()
            }

            val pos = formatSec(t)
            val dur = formatSec(d)
            val state = if (p) "⏸" else "▶"
            val srtInfo = if (repo.isEmpty) "SRT: —"
            else "SRT: ${repo.size} → #${cue?.index ?: "—"}"

            val offSign = if (repo.offsetMs >= 0) "+" else ""
            val offStr = "$offSign${"%.1f".format(repo.offsetMs / 1000.0)}s"

            val savedInfo = "💾 ${storage.count()}"

            statusText.text = "video: $pos / $dur  $state  rs=$r\n" +
                "$srtInfo  ov=${if (overlayOn) "ON" else "OFF"}  ⏱ $offStr\n" +
                "t=${"%.2f".format(t)}s  $savedInfo  tick=${System.currentTimeMillis() % 100000}"
        } catch (e: Exception) {
            statusText.text = "تعذر التحليل: ${e.message}"
        }
    }

    private fun formatSec(s: Double): String {
        if (s.isNaN() || s.isInfinite() || s < 0) return "--:--"
        val total = s.toInt()
        val m = total / 60
        val sec = total % 60
        return "%02d:%02d".format(m, sec)
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        webView.evaluateJavascript(
            "if (window.__sariAutoShowInterval) clearInterval(window.__sariAutoShowInterval);",
            null
        )
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
