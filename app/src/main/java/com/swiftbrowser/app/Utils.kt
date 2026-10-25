package com.swiftbrowser.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

object Utils {
    const val DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s"
    const val DEFAULT_HOME = "https://duckduckgo.com"

    fun applyNightMode(value: String?) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Turns whatever was typed into a URL or a search query URL. */
    fun toUrl(input: String, searchTemplate: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(t) || t.startsWith("about:") || t.startsWith("file:")) return t
        val looksLikeHost = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
        return if (looksLikeHost) "https://$t" else searchTemplate.replace("%s", Uri.encode(t))
    }

    fun clearBrowsingData(ctx: Context, wv: WebView?) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        val w = wv ?: WebView(ctx)
        w.clearCache(true)
        wv?.clearHistory()
        wv?.clearFormData()
    }
}

data class Bookmark(val title: String, val url: String)

object Bookmarks {
    private const val KEY = "bookmarks"

    fun all(ctx: Context): MutableList<Bookmark> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val out = mutableListOf<Bookmark>()
        try {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bookmark(o.getString("title"), o.getString("url")))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun has(ctx: Context, url: String?) = url != null && all(ctx).any { it.url == url }

    fun add(ctx: Context, title: String, url: String) {
        val list = all(ctx)
        if (list.none { it.url == url }) { list.add(0, Bookmark(title.ifBlank { url }, url)); save(ctx, list) }
    }

    fun remove(ctx: Context, url: String) = save(ctx, all(ctx).filter { it.url != url })
}
