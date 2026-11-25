package se.persgatan.nyttattse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Appen visar webbappen (GitHub Pages) och lägger till en brygga, window.TvBridge,
 * som webbappen använder för att hitta, parkoppla och styra Google TV:n.
 */
class MainActivity : Activity() {

    companion object {
        const val START_URL = "https://persgatan51.github.io/nytt-att-se/"
        const val HOST = "persgatan51.github.io"
        const val PATH = "/nytt-att-se"
        // Notiser från bevakningen öppnar appen med titelns TMDB-id (t.ex. "tv/1399").
        const val ACTION_OPEN = "se.persgatan.nyttattse.OPEN"
        const val EXTRA_OPEN = "open"
        private val TMDB_ID = Regex("^(movie|tv)/\\d{1,10}$")
        private val YT_LINK = Regex("https?://(www\\.|m\\.)?(youtube\\.com/(watch|shorts)|youtu\\.be/)\\S+", RegexOption.IGNORE_CASE)

        // Bara appens egen sida (inte andra Pages-projekt på samma konto) får använda TvBridge.
        fun isOurPage(uri: Uri?): Boolean {
            if (uri == null || uri.scheme != "https" || uri.host != HOST) return false
            val p = uri.path ?: return false
            return p == PATH || p.startsWith("$PATH/")
        }
    }

    // Sätts när en sida börjar laddas; läses från bryggans trådar.
    @Volatile private var trusted = false

    private lateinit var web: WebView
    private lateinit var tv: TvRemote
    private val worker = Executors.newSingleThreadExecutor()
    // Bevakningen har en egen tråd, så att en lång koll inte blockerar TV-kommandona.
    private val watchWorker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    // Det som senast delats till appen (Dela → Nytt att se), som JSON { text, subject }. Webbappen hämtar
    // det med TvBridge.takeShared() när sidan laddats, eller direkt via window.__onShare om den redan är öppen.
    @Volatile private var shared: String? = null

    private fun readShare(intent: Intent?) {
        val i = intent ?: return
        // Öppnas appen igen från "senaste appar" levereras samma delning en gång till – den är redan visad.
        if ((i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        // Tryck på en bevakningsnotis: öppna titeln i detaljvyn (samma väg som en delad TMDB-länk).
        if (i.action == ACTION_OPEN) {
            val id = i.getStringExtra(EXTRA_OPEN) ?: return
            if (!TMDB_ID.matches(id)) return
            shared = JSONObject().put("text", "https://www.themoviedb.org/$id").put("subject", "").put("src", "notif").toString()
            return
        }
        if (i.action != Intent.ACTION_SEND || i.type?.startsWith("text/") != true) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.take(2000) ?: ""
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.take(300) ?: ""
        if (text.isBlank() && subject.isBlank()) return
        // YouTube-appen delar ofta bara en länk. Titeln hämtas här (oEmbed), eftersom webbsidan kan stoppas av
        // CORS-regler. Misslyckas det lämnas länken över ändå, och webbappen försöker själv.
        val yt = YT_LINK.find(text)?.value
        if (yt != null && subject.isBlank() && text.replace(yt, "").isBlank()) {
            worker.execute {
                val title = ytTitle(yt)
                shared = JSONObject().put("text", text).put("subject", title ?: "").toString()
                runOnUiThread { if (trusted && ::web.isInitialized) web.evaluateJavascript("window.__onShare && window.__onShare()", null) }
            }
            return
        }
        shared = JSONObject().put("text", text).put("subject", subject).toString()
    }

    private fun ytTitle(url: String): String? {
        val c = URL("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 6000
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().readText().take(20000)).optString("title").take(300).ifBlank { null }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    // Utan nät: först sidan från cachen, annars en egen sida med "Försök igen".
    // 0 = vanlig laddning, 1 = cachen provas, 2 = sidan utan nät visas.
    private var fallback = 0
    private var cacheLoading = false

    private fun loadStart() {
        fallback = 0
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // no-cache: sidan hämtas alltid färsk vid start (GitHub Pages cachar annars i upp till 10 min),
        // men sparas ändå i cachen så att den kan visas när nätet saknas.
        web.loadUrl(START_URL, mapOf("Cache-Control" to "no-cache"))
    }

    private fun offlineHtml() = """<!doctype html><html lang="sv"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Ingen anslutning</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0f1115;color:#e8eaf0;
font-family:system-ui,sans-serif;text-align:center;padding:24px;box-sizing:border-box}h1{font-size:1.3rem}p{color:#9aa3b2}
a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:999px;background:#e50914;color:#fff;text-decoration:none;font-weight:600}</style>
</head><body><main><div style="font-size:3rem">📡</div><h1>Ingen anslutning</h1>
<p>Nytt att se behöver internet första gången. Kontrollera wifi eller mobildata.</p>
<a href="$START_URL">Försök igen</a></main></body></html>"""

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isOurPage(uri)) {
                    // "Försök igen" på sidan utan nät: ladda om som vid start.
                    if (fallback == 2) { loadStart(); return true }
                    return false
                }
                // Allt annat (Netflix, YouTube, TMDB, andra sidor på github.io …) öppnas i rätt app på telefonen.
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                trusted = isOurPage(url?.let { Uri.parse(it) })
                if (fallback == 1) cacheLoading = true
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Sidan från cachen är laddad: API-anropen ska gå mot nätet som vanligt.
                if (cacheLoading) { cacheLoading = false; view.settings.cacheMode = WebSettings.LOAD_DEFAULT }
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                trusted = isOurPage(url?.let { Uri.parse(it) })
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !isOurPage(request.url)) return
                if (fallback == 0) {
                    fallback = 1
                    view.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                    view.post { view.loadUrl(START_URL) }
                } else {
                    fallback = 2
                    cacheLoading = false
                    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    trusted = false
                    view.post { view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "utf-8", null) }
                }
            }

            // Systemet kan stänga WebViews renderare (t.ex. vid ont om minne). Utan detta dödas hela appen.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== web) return true
                trusted = false
                try { view.destroy() } catch (_: Exception) {}
                web = createWebView()
                setContentView(web)
                loadStart()
                return true
            }
        }
        w.addJavascriptInterface(Bridge(), "TvBridge")
        // Låt webbsidan sköta långa tryck (snabbmenyn) – inte WebViews egen textmarkering.
        w.setOnLongClickListener { true }
        w.isHapticFeedbackEnabled = false
        return w
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvRemote(this)
        if (savedInstanceState == null) readShare(intent)
        web = createWebView()
        setContentView(web)
        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            trusted = isOurPage(web.url?.let { Uri.parse(it) })
        } else loadStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
        if (shared != null && trusted) web.evaluateJavascript("window.__onShare && window.__onShare()", null)
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
        try {
            if (!trusted) throw TvRemote.TvException("Den här sidan får inte styra TV:n.")
            reply(id, true, block())
        }
        catch (e: TvRemote.TvException) { reply(id, false, e.message ?: "Fel") }
        catch (e: Exception) { reply(id, false, "Något gick fel: ${e.message ?: e.javaClass.simpleName}") }
    }

    inner class Bridge {
        @JavascriptInterface fun version(): String = "1"

        /** Det som delats till appen (JSON { text, subject }), eller "" – lämnas bara ut en gång. */
        @JavascriptInterface fun takeShared(): String {
            if (!trusted) return ""
            val s = shared ?: return ""
            shared = null
            return s
        }
        @JavascriptInterface fun getTv(): String = if (!trusted) "{}" else JSONObject()
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

        // Bevakningslistan från webbappen; kollas i bakgrunden av WatchJob och ger notiser.
        @JavascriptInterface fun setWatch(json: String) {
            if (!trusted) return
            // Skrivs med samma lås som WatchChecker.run, så att en pågående bakgrundskoll inte
            // skriver tillbaka en äldre lista över den nya (och utan att JavaScript får vänta).
            watchWorker.execute {
                synchronized(WatchChecker) {
                    getSharedPreferences("watch", MODE_PRIVATE).edit().putString("cfg", json).commit()
                }
            }
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    prefs.edit().putBoolean("askedNotif", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                }
                WatchJob.schedule(this@MainActivity)
            }
        }

        @JavascriptInterface fun checkWatchNow() {
            if (!trusted) return
            watchWorker.execute { try { WatchChecker.run(this@MainActivity) } catch (_: Exception) {} }
        }

        // Om notiser är avstängda visar webbappen en knapp under ⚙︎ som öppnar inställningen.
        @JavascriptInterface fun notifEnabled(): Boolean =
            getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() != false &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

        @JavascriptInterface fun openNotifSettings() = runOnUiThread {
            if (!trusted) return@runOnUiThread
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun openExternal(url: String) = runOnUiThread {
            if (!trusted) return@runOnUiThread
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
        }
    }
}
