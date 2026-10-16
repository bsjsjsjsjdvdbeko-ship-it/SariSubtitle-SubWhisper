package com.sari.subtitle.v2

import android.annotation.SuppressLint
import android.app.Activity
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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Sari Subtitle — المرحلة 3.3
 * - حفظ SRT تلقائياً بعد تحميله (مربوط بـ URL).
 * - استرجاع SRT عند فتح نفس URL.
 * - عرض عدد الملفات المحفوظة.
 */
class SariSubtitleActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_SRT = 1001
        private const val OFFSET_STEP_MS = 500L
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var subtitleOverlay: SubtitleOverlay

    private lateinit var settings: SubtitleSettings
    private lateinit var storage: SrtStorage

    private val handler = Handler(Looper.getMainLooper())
    private val repo = SubtitleRepository()
    private var polling = false
    private var overlayOn = false

    /** آخر SRT نصي محمّل (للحفظ عند الحاجة) */
    private var lastSrtText: String? = null

    /** URL الذي تم تحميل SRT من أجله */
    private var lastSrtUrl: String? = null

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

        findViewById<Button>(R.id.reloadXButton).setOnClickListener {
            urlInput.setText("https://x.com/")
            webView.loadUrl("https://x.com/")
        }

        findViewById<Button>(R.id.checkTimeButton).setOnClickListener {
            pollCurrentTimeOnce()
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

    /**
     * يبدأ إنشاء SRT عبر خدمة ويب خارجية. لا نزعم أن الخدمة مدمجة داخل التطبيق:
     * المستخدم ينشئ الملف في الموقع ثم يرجع ويستورده من زر SRT.
     */
    private fun openSrtGenerator() {
        val videoUrl = (urlInput.text.toString().trim().ifBlank { webView.url ?: "" }).trim()
        val uri = try { Uri.parse(videoUrl) } catch (_: Exception) { null }
        if (videoUrl.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "ألصق رابط فيديو صحيحاً أولاً", Toast.LENGTH_LONG).show()
            urlInput.requestFocus()
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", videoUrl))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("إنشاء ملف SRT عربي")
                .setMessage("نُسخ رابط الفيديو. سيفتح مولّد خارجي قد يدعم هذا الرابط. داخل الموقع: الصق الرابط، اختر الترجمة إلى العربية إن كان الخيار متاحاً، أنشئ الترجمة ونزّل ملف SRT. ثم ارجع إلى Sari Subtitle واضغط زر SRT لاستيراده.\n\nالخدمة الخارجية لها حدود مجانية وقد لا تدعم بعض الروابط الخاصة أو المحمية؛ لا يتم إنشاء SRT داخل التطبيق نفسه.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فتح مولّد SRT") { _, _ ->
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

    private fun openSrtPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_SRT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { loadSrtFromUri(it) }
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
