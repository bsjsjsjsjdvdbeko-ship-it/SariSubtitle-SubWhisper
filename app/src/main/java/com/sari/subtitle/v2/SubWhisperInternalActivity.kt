package com.sari.subtitle.v2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import android.util.Base64

/**
 * Opens the public SubWhisper website inside Sari Subtitle (not in an external app).
 * It does not reverse-engineer a private API. SRT files generated as Blob downloads
 * are intercepted and returned to the main activity when the site exposes a normal
 * downloadable .srt link.
 */
class SubWhisperInternalActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INITIAL_URL = "subwhisper_initial_url"
        const val EXTRA_SRT_TEXT = "subwhisper_srt_text"
        private const val SITE_URL = "https://sub.kuandre.com/"
        private const val MAX_SRT_BYTES = 12 * 1024 * 1024
    }

    private lateinit var webView: WebView
    private var resultDelivered = false
    private var initialUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
        }
        top.addView(Button(this).apply {
            text = "رجوع"
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "SubWhisper داخل Sari Subtitle"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(12, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "تعمل عملية الاستخراج عبر خدمة SubWhisper داخل هذه الشاشة. بعد إنشاء الترجمة، نزّل ملف SRT من الصفحة؛ سيحاول التطبيق التقاطه واستيراده تلقائياً. قد لا تُلتقط الملفات إذا غيّرت الخدمة طريقة التنزيل."
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(12, 4, 12, 8)
        })
        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(SrtDownloadBridge(), "SariSrtBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectSrtDownloadHook()
                if (url?.startsWith(SITE_URL) == true && initialUrl.isNotBlank()) {
                    prefillLink(initialUrl)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // Keep normal web navigation inside the app; only block non-web schemes.
                return uri.scheme !in listOf("http", "https", "about", "blob", "data")
            }
        }
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDirectDownload(url, userAgent, contentDisposition, mimeType)
        })
        webView.loadUrl(SITE_URL)
    }

    private fun prefillLink(url: String) {
        val quoted = JSONObject.quote(url)
        val js = """
            (function(){
              var wanted = $quoted;
              var attempts = 0;
              var timer = setInterval(function(){
                attempts++;
                var inputs = Array.from(document.querySelectorAll('input'));
                var input = inputs.find(function(el){
                  var p = ((el.placeholder || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
                  return /youtube|tiktok|instagram|link|url|連結/.test(p) && !/email|password/.test(p);
                });
                if (input) {
                  input.value = wanted;
                  input.dispatchEvent(new Event('input', {bubbles:true}));
                  input.dispatchEvent(new Event('change', {bubbles:true}));
                  clearInterval(timer);
                }
                if (attempts > 30) clearInterval(timer);
              }, 400);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectSrtDownloadHook() {
        val js = """
            (function() {
              if (window.__sariSrtHookInstalled) return;
              window.__sariSrtHookInstalled = true;

              // دالة مساعدة لإرسال النص إلى Kotlin
              window.__sariSendSrt = function(text) {
                if (window.SariSrtBridge) {
                  var base64 = btoa(unescape(encodeURIComponent(text)));
                  window.SariSrtBridge.receiveSrt('subtitles.srt', 'text/plain', base64);
                }
              };

              // دالة لفحص ما إذا كان النص يشبه SRT
              window.__sariLooksLikeSrt = function(text) {
                return /\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}/.test(text);
              };

              // 1. اعتراض fetch
              var originalFetch = window.fetch;
              window.fetch = function() {
                return originalFetch.apply(this, arguments).then(function(response) {
                  var clone = response.clone();
                  clone.text().then(function(text) {
                    if (text && window.__sariLooksLikeSrt(text)) {
                      window.__sariSendSrt(text);
                    }
                  }).catch(function() {});
                  return response;
                });
              };

              // 2. اعتراض XMLHttpRequest
              var originalXhrOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                  try {
                    if (this.responseText && window.__sariLooksLikeSrt(this.responseText)) {
                      window.__sariSendSrt(this.responseText);
                    }
                  } catch(e) {}
                });
                return originalXhrOpen.apply(this, arguments);
              };

              // 3. اعتراض window.open
              var originalOpen = window.open;
              window.open = function(url) {
                if (url && (url.startsWith('blob:') || url.startsWith('data:'))) {
                  fetch(url).then(r => r.text()).then(text => {
                    if (window.__sariLooksLikeSrt(text)) window.__sariSendSrt(text);
                  });
                  return null;
                }
                return originalOpen.apply(this, arguments);
              };

              // 4. اعتراض النقر على روابط التنزيل
              document.addEventListener('click', function(e) {
                var target = e.target.closest('a');
                if (!target) return;
                var href = target.href;
                if (href && (href.startsWith('blob:') || href.startsWith('data:'))) {
                  e.preventDefault();
                  e.stopPropagation();
                  fetch(href).then(r => r.text()).then(text => {
                    if (window.__sariLooksLikeSrt(text)) window.__sariSendSrt(text);
                  });
                }
              }, true);

              // 5. اعتراض createElement('a')
              var originalCreateElement = document.createElement;
              document.createElement = function(tag) {
                var el = originalCreateElement.call(document, tag);
                if (tag.toLowerCase() === 'a') {
                  var originalClick = el.click;
                  el.click = function() {
                    if (this.href && (this.href.startsWith('blob:') || this.href.startsWith('data:'))) {
                      fetch(this.href).then(r => r.text()).then(text => {
                        if (window.__sariLooksLikeSrt(text)) window.__sariSendSrt(text);
                      });
                      return;
                    }
                    return originalClick.apply(this, arguments);
                  };
                }
                return el;
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleDirectDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "يجري تجهيز ملف التنزيل داخل الصفحة…", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(url)
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription("تنزيل من SubWhisper")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "بدأ تنزيل الملف إلى مجلد Downloads. إن كان SRT، ارجع واضغط زر SRT لاستيراده.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تنزيل الملف: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    inner class SrtDownloadBridge {
        @JavascriptInterface
        fun receiveSrt(fileName: String?, mimeType: String?, base64: String?) {
            if (resultDelivered) return
            try {
                val encoded = base64.orEmpty()
                if (encoded.isBlank() || encoded.length > 17_000_000) return
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty() || bytes.size > MAX_SRT_BYTES) return
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val looksLikeSrt = Regex("(?m)^\\s*\\d+\\s*\\R\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(text)
                if (!looksLikeSrt) {
                    runOnUiThread { Toast.makeText(this@SubWhisperInternalActivity, "تم رصد تنزيل، لكنه لا يبدو ملف SRT صالحاً", Toast.LENGTH_LONG).show() }
                    return
                }
                runOnUiThread {
                    if (resultDelivered || isFinishing) return@runOnUiThread
                    resultDelivered = true
                    val resultIntent = Intent().putExtra(EXTRA_SRT_TEXT, text)
                    setResult(Activity.RESULT_OK, resultIntent)
                    Toast.makeText(this@SubWhisperInternalActivity, "تم التقاط SRT وإرجاعه إلى Sari Subtitle", Toast.LENGTH_SHORT).show()
                    // تأخير finish حتى يُستدعى onActivityResult
                    webView.postDelayed({
                        if (!isFinishing) finish()
                    }, 300)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@SubWhisperInternalActivity, "تعذّر قراءة ملف SRT: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("SariSrtBridge")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
