package com.swiftbrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var defaultUa: String
    private var incognito = false
    private var desktop = false
    private var loading = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Utils.applyNightMode(prefs.getString("theme", "system"))
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        defaultUa = WebSettings.getDefaultUserAgent(this)

        setupWebView()
        setupUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    b.addressInput.hasFocus() -> { b.addressInput.clearFocus(); hideKeyboard() }
                    b.webView.canGoBack() -> b.webView.goBack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState != null) b.webView.restoreState(savedInstanceState)
        else load(intent?.dataString ?: homepage())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { load(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        b.webView.onResume()
        applyPrefs()
    }

    override fun onPause() {
        b.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && (incognito || prefs.getBoolean("clear_on_exit", false))) {
            Utils.clearBrowsingData(this, b.webView)
        }
        (b.webView.parent as? ViewGroup)?.removeView(b.webView)
        b.webView.destroy()
        super.onDestroy()
    }

    // ---------- setup ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = b.webView
        wv.settings.apply {
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when (request.url.scheme) {
                    "https", "about", "data", "blob" -> false
                    "http" -> if (prefs.getBoolean("https_only", false)) {
                        view.loadUrl(url.replaceFirst("http://", "https://")); true
                    } else false
                    else -> { openExternal(url); true }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                b.progress.visibility = View.VISIBLE
                updateAddress(url)
                updateReloadIcon()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                b.swipeRefresh.isRefreshing = false
                b.progress.visibility = View.GONE
                updateAddress(url)
                updateReloadIcon()
                updateBookmarkIcon()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                        "<body style='font-family:sans-serif;text-align:center;padding:64px 24px;color:#666'>" +
                        "<h2>Can\u2019t reach this page</h2><p>${error.description}</p></body></html>"
                    view.loadDataWithBaseURL(request.url.toString(), html, "text/html", "UTF-8", request.url.toString())
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.setProgressCompat(newProgress, true)
                b.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() = hideCustomView()
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (Build.VERSION.SDK_INT <= 28 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                toast(getString(R.string.storage_permission))
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(getString(R.string.downloading, name))
        }
    }

    private fun setupUi() {
        b.addressInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { go(v.text.toString()); true } else false
        }
        b.addressInput.setOnFocusChangeListener { _, focused ->
            if (focused) b.addressInput.post { b.addressInput.selectAll() } else updateAddress(b.webView.url)
        }
        b.reloadButton.setOnClickListener {
            if (loading) { b.webView.stopLoading(); loading = false; updateReloadIcon() } else b.webView.reload()
        }
        b.btnBack.setOnClickListener { if (b.webView.canGoBack()) b.webView.goBack() }
        b.btnForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }
        b.btnHome.setOnClickListener { load(homepage()) }
        b.btnBookmark.setOnClickListener { toggleBookmark() }
        b.btnMenu.setOnClickListener { showMenu(it) }

        b.swipeRefresh.setColorSchemeResources(R.color.primary)
        b.swipeRefresh.setOnRefreshListener { b.webView.reload() }
        b.swipeRefresh.setOnChildScrollUpCallback { _, _ -> b.webView.scrollY > 0 }
    }

    private fun applyPrefs() {
        b.webView.settings.apply {
            javaScriptEnabled = prefs.getBoolean("javascript", true)
            textZoom = prefs.getString("text_size", "100")?.toIntOrNull() ?: 100
            javaScriptCanOpenWindowsAutomatically = !prefs.getBoolean("block_popups", true)
            userAgentString = if (desktop) desktopUa else defaultUa
            cacheMode = if (incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(b.webView, prefs.getBoolean("third_party_cookies", false))
        b.incognitoBadge.visibility = if (incognito) View.VISIBLE else View.GONE
    }

    // ---------- actions ----------

    private fun homepage() = prefs.getString("homepage", Utils.DEFAULT_HOME)?.ifBlank { Utils.DEFAULT_HOME } ?: Utils.DEFAULT_HOME

    private fun load(url: String) {
        if (url.isNotBlank()) b.webView.loadUrl(url)
    }

    private fun go(text: String) {
        val url = Utils.toUrl(text, prefs.getString("search_engine", Utils.DEFAULT_SEARCH) ?: Utils.DEFAULT_SEARCH)
        b.addressInput.clearFocus()
        hideKeyboard()
        load(url)
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            menu.findItem(R.id.action_desktop).isChecked = desktop
            menu.findItem(R.id.action_incognito).isChecked = incognito
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_share -> share()
                    R.id.action_bookmarks -> showBookmarks()
                    R.id.action_desktop -> { desktop = !desktop; applyPrefs(); b.webView.reload() }
                    R.id.action_incognito -> {
                        incognito = !incognito
                        if (!incognito) Utils.clearBrowsingData(this@MainActivity, b.webView)
                        applyPrefs()
                        toast(getString(if (incognito) R.string.incognito_on else R.string.incognito_off))
                    }
                    R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun share() {
        val url = b.webView.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
        startActivity(Intent.createChooser(send, null))
    }

    private fun toggleBookmark() {
        val url = b.webView.url ?: return
        if (Bookmarks.has(this, url)) {
            Bookmarks.remove(this, url); toast(getString(R.string.bookmark_removed))
        } else {
            Bookmarks.add(this, b.webView.title ?: url, url); toast(getString(R.string.bookmark_added))
        }
        updateBookmarkIcon()
    }

    private fun showBookmarks() {
        val list = Bookmarks.all(this)
        if (list.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.bookmarks)
                .setMessage(R.string.no_bookmarks).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map { it.title }.toMutableList())
        val lv = ListView(this).apply { this.adapter = adapter }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks).setMessage(R.string.bookmark_hint)
            .setView(lv).setNegativeButton(R.string.close, null).create()
        lv.setOnItemClickListener { _, _, i, _ -> load(list[i].url); dialog.dismiss() }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            Bookmarks.remove(this, list[i].url)
            list.removeAt(i); adapter.remove(adapter.getItem(i)); updateBookmarkIcon(); true
        }
        dialog.show()
    }

    // ---------- ui helpers ----------

    private fun updateAddress(url: String?) {
        val u = url ?: ""
        b.siteIcon.setImageResource(if (u.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_search)
        if (!b.addressInput.hasFocus()) {
            val shown = if (u.startsWith("data:") || u == "about:blank") ""
            else u.removePrefix("https://").removePrefix("www.").trimEnd('/')
            b.addressInput.setText(shown)
        }
    }

    private fun updateReloadIcon() =
        b.reloadButton.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)

    private fun updateBookmarkIcon() =
        b.btnBookmark.setImageResource(
            if (Bookmarks.has(this, b.webView.url)) R.drawable.ic_star_filled else R.drawable.ic_star
        )

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view; customCallback = callback
        b.fullscreenContainer.addView(
            view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        b.fullscreenContainer.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        b.fullscreenContainer.removeView(v)
        b.fullscreenContainer.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null; customCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply { addCategory(Intent.CATEGORY_BROWSABLE); selector = null }
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(i)
        } catch (e: Exception) {
            toast(getString(R.string.no_app))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
