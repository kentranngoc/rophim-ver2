package com.rophim.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExec = Executor { handler.post(it) }
    private val proxy = DohProxy()

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var usingDoh = false
    private var discovering = false
    private var gen = 0                  // đánh dấu lần tải, bỏ qua callback cũ
    private var lastBack = 0L
    private var loadedOk = false
    private var loadId = 0
    private var handledId = -1
    private var lastError = ""
    private var preferDoh: Boolean
        get() = prefs.getBoolean("preferDoh", false)
        set(v) = prefs.edit().putBoolean("preferDoh", v).apply()

    private val prefs by lazy { getSharedPreferences("rophim", Context.MODE_PRIVATE) }
    private var base: String
        get() = prefs.getString("base", "https://rophim1.ink")!!
        set(v) = prefs.edit().putString("base", v).apply()
    private var keyword: String
        get() = prefs.getString("keyword", "rophim")!!
        set(v) = prefs.edit().putString("keyword", v).apply()

    // Tự ẩn con trỏ chuột sau 3 giây không thao tác
    private val hideCursorRunnable = Runnable {
        if (::cursor.isInitialized) {
            cursor.cursorVisible = false
        }
    }

    private fun wakeCursor() {
        if (!::cursor.isInitialized) return
        cursor.cursorVisible = true
        handler.removeCallbacks(hideCursorRunnable)
        handler.postDelayed(hideCursorRunnable, 3000L)
    }

    // Tham chiếu ô nhập trong hộp thoại tìm kiếm TV để điền kết quả giọng nói
    private var activeSearchInput: EditText? = null
    private var activeSearchDialog: AlertDialog? = null

    companion object {
        private const val REQ_VOICE_SEARCH = 9001
    }

    private fun isOurHost(host: String?) = host != null && host.contains(keyword, ignoreCase = true)

    // ------------------------------------------------------------ vòng đời
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        web = WebView(this)
        cursor = CursorView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemBars()
        wakeCursor()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        // Giống Chrome thật, bỏ dấu hiệu "WebView"
        s.userAgentString = s.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(TvBridge(), "AndroidTV")
        web.webViewClient = client
        web.webChromeClient = chrome
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        startLoad()
    }

    override fun onResume() { super.onResume(); web.onResume(); hideSystemBars(); wakeCursor() }
    override fun onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(hideCursorRunnable); proxy.stop(); web.destroy(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ------------------------------------------------------------ Bridge bắt sự kiện bấm ô tìm kiếm trên web
    inner class TvBridge {
        @JavascriptInterface
        fun openTvSearch(currentVal: String?) {
            handler.post {
                if (!hasDialog) {
                    showTvSearchDialog(currentVal ?: "")
                }
            }
        }
    }

    private fun injectSearchHook() {
        val js = """
            (function() {
                if (window.__tvSearchHooked) return;
                window.__tvSearchHooked = true;
                function isSearchInput(el) {
                    if (!el || el.tagName !== 'INPUT') return false;
                    var t = (el.type || 'text').toLowerCase();
                    if (t !== 'text' && t !== 'search' && t !== '') return false;
                    var ph = (el.placeholder || '').toLowerCase();
                    var nm = (el.name || '').toLowerCase();
                    var id = (el.id || '').toLowerCase();
                    var cls = (el.className || '').toString().toLowerCase();
                    return ph.indexOf('tìm') !== -1 || ph.indexOf('search') !== -1 ||
                           nm.indexOf('search') !== -1 || nm.indexOf('keyword') !== -1 || nm === 'q' ||
                           id.indexOf('search') !== -1 || cls.indexOf('search') !== -1 || t === 'search';
                }
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    if (isSearchInput(el)) {
                        window.__lastSearchInput = el;
                        if (window.AndroidTV && window.AndroidTV.openTvSearch) {
                            window.AndroidTV.openTvSearch(el.value || '');
                        }
                    }
                }, true);
                document.addEventListener('focusin', function(e) {
                    var el = e.target;
                    if (isSearchInput(el)) {
                        window.__lastSearchInput = el;
                        if (window.AndroidTV && window.AndroidTV.openTvSearch) {
                            window.AndroidTV.openTvSearch(el.value || '');
                        }
                    }
                }, true);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun submitSearchToWeb(query: String) {
        val quoted = JSONObject.quote(query)
        val js = """
            (function() {
                var q = $quoted;
                var el = window.__lastSearchInput;
                if (!el || !document.body.contains(el)) {
                    var inputs = document.querySelectorAll('input[type="text"], input[type="search"], input:not([type])');
                    for (var i = 0; i < inputs.length; i++) {
                        var ph = (inputs[i].placeholder || '').toLowerCase();
                        if (ph.indexOf('tìm') !== -1 || ph.indexOf('search') !== -1) {
                            el = inputs[i];
                            break;
                        }
                    }
                }
                if (el) {
                    el.focus();
                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    if (nativeSetter) {
                        nativeSetter.call(el, q);
                    } else {
                        el.value = q;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    if (el.form) {
                        try { el.form.submit(); } catch(err) {}
                    }
                }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------ Hộp thoại Tìm kiếm TV: Bảng chữ cái + Bàn phím ảo + Giọng nói
    private fun showTvSearchDialog(initialText: String = "") {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#181A20"))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val editText = EditText(this).apply {
            setText(initialText)
            setSelection(initialText.length)
            hint = "Nhập tên phim hoặc bấm micro để nói..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#262932"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        activeSearchInput = editText

        val btnVoice = Button(this).apply {
            text = "🎤 Nói"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startVoiceRecognition() }
        }

        val btnSystemIme = Button(this).apply {
            text = "⌨ Bàn phím"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                editText.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
            }
        }

        topRow.addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        topRow.addView(btnVoice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })
        topRow.addView(btnSystemIme, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(topRow)

        val keys = listOf(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
            "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X",
            "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "DẤU CÁCH", "XÓA 1 KÝ TỰ", "XÓA HẾT", "TÌM PHIM"
        )

        val grid = GridLayout(this).apply {
            columnCount = 12
            setPadding(0, dp(12), 0, 0)
        }

        var dialogRef: AlertDialog? = null

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = editText.text.toString().trim()
                dialogRef?.dismiss()
                if (q.isNotEmpty()) submitSearchToWeb(q)
                true
            } else false
        }

        for (k in keys) {
            val isAction = k.length > 1
            val btn = Button(this).apply {
                text = k
                textSize = if (isAction) 12f else 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(
                    when (k) {
                        "TÌM PHIM" -> Color.parseColor("#2E7D32")
                        "XÓA HẾT", "XÓA 1 KÝ TỰ" -> Color.parseColor("#455A64")
                        else -> Color.parseColor("#2A2E39")
                    }
                )
                setPadding(dp(4), dp(6), dp(4), dp(6))
                minimumWidth = 0
                minimumHeight = 0
                setOnClickListener {
                    val cur = editText.text.toString()
                    when (k) {
                        "DẤU CÁCH" -> {
                            editText.setText("$cur ")
                            editText.setSelection(editText.text.length)
                        }
                        "XÓA 1 KÝ TỰ" -> {
                            if (cur.isNotEmpty()) {
                                editText.setText(cur.dropLast(1))
                                editText.setSelection(editText.text.length)
                            }
                        }
                        "XÓA HẾT" -> editText.setText("")
                        "TÌM PHIM" -> {
                            val q = cur.trim()
                            dialogRef?.dismiss()
                            if (q.isNotEmpty()) submitSearchToWeb(q)
                        }
                        else -> {
                            editText.setText(cur + k.lowercase(Locale.getDefault()))
                            editText.setSelection(editText.text.length)
                        }
                    }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = if (isAction) GridLayout.LayoutParams.WRAP_CONTENT else dp(44)
                height = dp(40)
                if (isAction) {
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 3)
                }
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(btn, params)
        }
        container.addView(grid)

        val builder = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("Đóng", null)

        hasDialog = true
        val dialog = builder.create()
        dialogRef = dialog
        activeSearchDialog = dialog
        dialog.setOnDismissListener {
            hasDialog = false
            activeSearchInput = null
            activeSearchDialog = null
            hideSystemBars()
            wakeCursor()
        }
        dialog.show()

        // Tự động bật bàn phím mềm hệ thống nếu TV Box có cài sẵn
        handler.postDelayed({
            editText.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 200L)
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên phim bạn muốn tìm...")
        }
        try {
            startActivityForResult(intent, REQ_VOICE_SEARCH)
        } catch (_: ActivityNotFoundException) {
            toast("TV Box chưa cài dịch vụ nhận diện giọng nói Google")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_SEARCH && resultCode == RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.firstOrNull()?.trim() ?: ""
            if (spoken.isNotEmpty()) {
                val input = activeSearchInput
                if (input != null) {
                    input.setText(spoken)
                    input.setSelection(spoken.length)
                } else {
                    showTvSearchDialog(spoken)
                }
                activeSearchDialog?.dismiss()
                submitSearchToWeb(spoken)
            }
        }
    }

    // ------------------------------------------------------------ tải trang (GIỮ NGUYÊN LOGIC CŨ, KHÔNG ĐỤNG DNS)
    /** Luôn thử tải thẳng (không DNS) trước để giữ tốc độ bình thường. */
    private fun startLoad() {
        setProxy(preferDoh) { load(base) }
    }

    private fun load(url: String) {
        loadedOk = false
        loadId++
        gen++
        val g = gen
        web.loadUrl(url)
        handler.postDelayed({
            if (g == gen && web.progress < 100) onMainFailed("quá 20 giây không tải xong")
        }, 20_000)
    }

    /** Link lỗi: lần 1 bật DNS dự phòng, lần 2 đi dò tên miền mới. */
    private fun onMainFailed(reason: String = "") {
        if (handledId == loadId) return
        handledId = loadId
        lastError = reason
        gen++
        if (discovering) return
        if (!usingDoh) {
            toast("Link lỗi ($reason), đang dùng DNS dự phòng…")
            setProxy(true) { load(base) }
        } else {
            discover()
        }
    }

    private fun discover() {
        discovering = true
        toast("Đang dò link mới, vui lòng đợi…")
        val host = Uri.parse(base).host ?: "rophim1.ink"
        Thread {
            val found = DomainFinder.find(host, keyword, proxy.port)
            handler.post {
                discovering = false
                if (found != null) {
                    base = found
                    toast("Đã cập nhật link mới: $found")
                    load(found)
                } else {
                    web.loadData(
                        "<html><body style='background:#111;color:#eee;font-size:28px;padding:60px'>" +
                                "<h2>Chưa tìm được link mới</h2><p>Lỗi: $lastError</p><p>Bấm nút MENU trên remote → \"Nhập link khác\" " +
                                "hoặc \"Thử lại\".</p></body></html>", "text/html", "utf-8"
                    )
                }
            }
        }.start()
    }

    private fun setProxy(on: Boolean, then: () -> Unit) {
        usingDoh = on
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (on) toast("WebView trên máy chưa hỗ trợ DNS dự phòng, hãy cập nhật Android System WebView")
            then(); return
        }
        if (on) {
            proxy.start()
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:${proxy.port}").build()
            ProxyController.getInstance().setProxyOverride(cfg, mainExec, then)
        } else {
            ProxyController.getInstance().clearProxyOverride(mainExec, then)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ WebViewClient
    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val host = r.url.host
            if (isOurHost(host) || r.url.scheme == "about") return false
            if (!r.hasGesture() && r.isForMainFrame && !loadedOk) {
                handler.post { onMainFailed("bị chuyển sang trang lạ") }
            }
            return true
        }

        override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
            val h = Uri.parse(url).host
            if (isOurHost(h) && "https://$h" != base) base = "https://$h"
        }

        override fun onPageFinished(v: WebView, url: String) {
            if (isOurHost(Uri.parse(url).host)) {
                loadedOk = true
                gen++
                preferDoh = usingDoh
                injectSearchHook()
            }
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (r.isForMainFrame && e.description?.contains("ERR_ABORTED") != true) handler.post { onMainFailed(e.description?.toString() ?: "lỗi mạng") }
        }

        @Deprecated("API 21-22")
        override fun onReceivedError(v: WebView, code: Int, desc: String?, failingUrl: String?) {
            if (android.os.Build.VERSION.SDK_INT < 23 && failingUrl == v.url) handler.post { onMainFailed(desc ?: "lỗi mạng") }
        }

        override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, res: WebResourceResponse) {
            if (r.isForMainFrame && res.statusCode >= 500) handler.post { onMainFailed("HTTP ${res.statusCode}") }
        }
    }

    // ------------------------------------------------------------ Chrome client (popup + toàn màn hình)
    private val chrome = object : WebChromeClient() {

        override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, msg: Message): Boolean {
            val tmp = WebView(this@MainActivity)
            tmp.settings.javaScriptEnabled = false
            fun judge(url: String?) {
                if (url == null || url.startsWith("about:")) return
                val u = Uri.parse(url)
                handler.post {
                    if (isOurHost(u.host)) web.loadUrl(url)
                    tmp.stopLoading(); tmp.destroy()
                }
            }
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    judge(r.url.toString()); return true
                }
                override fun onPageStarted(v: WebView, url: String, f: Bitmap?) = judge(url)
            }
            (msg.obj as WebView.WebViewTransport).webView = tmp
            msg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
            if (customView != null) { cb.onCustomViewHidden(); return }
            customView = view; customCallback = cb
            root.addView(view, root.indexOfChild(cursor), FrameLayout.LayoutParams(-1, -1))
            web.visibility = View.INVISIBLE
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden(); customCallback = null
            web.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------ Điều khiển bằng remote (Tăng tốc chuột + tự ẩn sau 3s)
    private fun target(): View = customView ?: web

    private fun tap() {
        wakeCursor()
        val t = SystemClock.uptimeMillis()
        val v = target()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, cursor.cx, cursor.cy, 0)
            v.dispatchTouchEvent(e); e.recycle()
        }
    }

    private fun moveCursor(dx: Int, dy: Int, repeat: Int) {
        wakeCursor()
        // Tốc độ khởi điểm nhanh hơn (34px) và tăng tốc mượt khi giữ phím điều hướng (lên tới 130px/bước)
        val step = 34f + minOf(repeat * 12f, 96f)
        val m = 28f
        var nx = cursor.cx + dx * step
        var ny = cursor.cy + dy * step
        // Chạm mép trên/dưới thì cuộn trang nhanh hơn
        if (customView == null) {
            if (dy > 0 && ny > cursor.height - m) web.scrollBy(0, (step * 2.5f).toInt())
            if (dy < 0 && ny < m) web.scrollBy(0, -(step * 2.5f).toInt())
        }
        nx = nx.coerceIn(0f, cursor.width.toFloat() - 2)
        ny = ny.coerceIn(0f, cursor.height.toFloat() - 2)
        cursor.cx = nx; cursor.cy = ny
        cursor.invalidate()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (hasDialog) return super.dispatchKeyEvent(e)
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveCursor(0, -1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveCursor(0, 1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveCursor(-1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveCursor(1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!down && e.eventTime - e.downTime < 800) tap()
                return true
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (!down) showTvSearchDialog("")
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (!down) showMenu(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (!down) goBack()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun goBack() {
        when {
            customView != null -> chrome.onHideCustomView()
            web.canGoBack() -> web.goBack()
            SystemClock.uptimeMillis() - lastBack < 2000 -> finish()
            else -> { lastBack = SystemClock.uptimeMillis(); toast("Bấm Back lần nữa để thoát") }
        }
    }

    // ------------------------------------------------------------ Menu
    private var hasDialog = false

    private fun showMenu() {
        val items = arrayOf(
            "Tìm kiếm phim (Bàn phím / Giọng nói)",
            "Tải lại trang",
            "Thử lại (dò link mới)",
            "Nhập link khác",
            "Về lại trang chủ",
            "Luôn dùng DNS dự phòng: " + (if (preferDoh) "BẬT" else "TẮT"),
            "Thoát"
        )
        show(AlertDialog.Builder(this).setTitle("Link hiện tại: $base").setItems(items) { _, i ->
            when (i) {
                0 -> showTvSearchDialog("")
                1 -> web.reload()
                2 -> { discovering = false; setProxy(true) { discover() } }
                3 -> askUrl()
                4 -> load(base)
                5 -> { preferDoh = !preferDoh; startLoad() }
                6 -> finish()
            }
        })
    }

    private fun askUrl() {
        val et = EditText(this)
        et.setText(base)
        et.setSingleLine()
        show(AlertDialog.Builder(this).setTitle("Nhập link trang phim").setView(et)
            .setPositiveButton("Mở") { _, _ ->
                var u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http")) u = "https://$u"
                    val h = Uri.parse(u).host
                    if (h != null) {
                        val parts = h.removePrefix("www.").split(".")
                        val label = parts[maxOf(parts.size - 2, 0)].trimEnd { it.isDigit() }
                        if (label.length >= 3) keyword = label
                    }
                    base = u
                    startLoad()
                }
            }.setNegativeButton("Huỷ", null))
    }

    private fun show(b: AlertDialog.Builder) {
        hasDialog = true
        val d = b.create()
        d.setOnDismissListener { hasDialog = false; hideSystemBars(); wakeCursor() }
        d.show()
    }
}
