package se.persgatan.nyttattse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Appen visar webbappen (GitHub Pages) och lägger till en brygga, window.TvBridge,
 * som webbappen använder för att hitta, parkoppla och styra Google TV:n.
 */
class MainActivity : Activity() {

    companion object {
        const val START_URL = "https://persgatan51.github.io/nytt-att-se/"
        const val HOST = "persgatan51.github.io"
    }

    private lateinit var web: WebView
    private lateinit var tv: TvRemote
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvRemote(this)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == HOST) return false
                // Allt annat (Netflix, YouTube, TMDB …) öppnas i rätt app på telefonen.
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "TvBridge")
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Låt webbappen stänga öppna rutor först; annars vanlig bakåt.
        web.evaluateJavascript("(window.__tvBack && window.__tvBack()) ? 'handled' : 'no'") { r ->
            if (r?.contains("handled") != true) {
                if (web.canGoBack()) web.goBack() else finishAfterTransition()
            }
        }
    }

    private fun reply(id: String, ok: Boolean, data: String) = runOnUiThread {
        web.evaluateJavascript(
            "window.__tvCb && window.__tvCb(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(data)})", null
        )
    }

    private fun async(id: String, block: () -> String) = worker.execute {
        try { reply(id, true, block()) }
        catch (e: TvRemote.TvException) { reply(id, false, e.message ?: "Fel") }
        catch (e: Exception) { reply(id, false, "Något gick fel: ${e.message ?: e.javaClass.simpleName}") }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): String = "1"
        @JavascriptInterface fun getTv(): String = JSONObject()
            .put("host", prefs.getString("host", "") ?: "")
            .put("name", prefs.getString("name", "") ?: "")
            .put("paired", prefs.getBoolean("paired", false)).toString()

        @JavascriptInterface fun discover(id: String) = async(id) {
            val arr = JSONArray()
            tv.discover().forEach { arr.put(JSONObject().put("name", it.name).put("host", it.host)) }
            arr.toString()
        }

        @JavascriptInterface fun startPairing(host: String, name: String, id: String) = async(id) {
            tv.startPairing(host)
            prefs.edit().putString("host", host).putString("name", name).putBoolean("paired", false).apply()
            "ok"
        }

        @JavascriptInterface fun finishPairing(code: String, id: String) = async(id) {
            tv.finishPairing(code)
            prefs.edit().putBoolean("paired", true).apply()
            "ok"
        }

        @JavascriptInterface fun launch(url: String, id: String) = async(id) {
            val host = prefs.getString("host", "") ?: ""
            if (host.isEmpty()) throw TvRemote.TvException("Välj och parkoppla en TV under ⚙︎ först.")
            tv.launch(host, url)
            "ok"
        }

        @JavascriptInterface fun openExternal(url: String) = runOnUiThread {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }
}
