package se.persgatan.nyttattse

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bakgrundskoll av bevakningen (ungefär två gånger per dygn, när telefonen har nät):
 *  - titlar i "Vill se" som dykt upp på någon av dina tjänster
 *  - serier du sett eller vill se som fått en ny säsong
 * Webbappen lämnar över listan via TvBridge.setWatch(); här används bara TMDB.
 */
class WatchJob : JobService() {
    companion object {
        private const val JOB_ID = 4242
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchJob::class.java))
                    .setPeriodic(TimeUnit.HOURS.toMillis(12))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
    }

    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        thread = Thread {
            try { WatchChecker.run(this) } catch (_: Exception) {}
            jobFinished(params, false)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        thread?.interrupt()
        return true
    }
}

object WatchChecker {
    private const val CHANNEL = "bevakning"

    @Synchronized
    fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
        val cfg = JSONObject(prefs.getString("cfg", null) ?: return)
        val key = cfg.optString("key")
        if (key.isEmpty()) return
        val services = cfg.optJSONArray("services") ?: JSONArray()
        // Notiser som redan skickats – sparas separat så att webbappen inte skriver över dem.
        val notified = JSONArray(prefs.getString("notified", "[]")).let { a ->
            LinkedHashSet<String>().apply { for (i in 0 until a.length()) add(a.getString(i)) }
        }

        val want = cfg.optJSONArray("want") ?: JSONArray()
        for (i in 0 until want.length()) {
            if (Thread.currentThread().isInterrupted) return
            val w = want.getJSONObject(i)
            val id = w.optString("id")
            val before = w.optJSONArray("svcs").strings()
            val res = tmdb(key, "/$id/watch/providers") ?: continue
            val se = res.optJSONObject("results")?.optJSONObject("SE") ?: JSONObject()
            val names = mutableListOf<String>()
            for (t in listOf("flatrate", "free", "ads")) se.optJSONArray(t)?.let { a ->
                for (j in 0 until a.length()) names += a.getJSONObject(j).optString("provider_name").trim()
            }
            val now = mutableListOf<String>()
            val newLabels = mutableListOf<String>()
            for (j in 0 until services.length()) {
                val s = services.getJSONObject(j)
                val re = try { Regex(s.optString("pattern"), RegexOption.IGNORE_CASE) } catch (_: Exception) { continue }
                if (names.any { re.matches(it) }) {
                    now += s.optString("key")
                    if (s.optString("key") !in before) newLabels += s.optString("label")
                }
            }
            val added = now.filter { it !in before }
            if (added.isNotEmpty()) {
                val nid = "avail|$id|" + added.joinToString(",")
                if (notified.add(nid)) notify(ctx, nid.hashCode(),
                    "${w.optString("title")} finns nu på ${newLabels.joinToString(" och ")}",
                    "Från din Vill se-lista. Tryck för att öppna Nytt att se.")
            }
            w.put("svcs", JSONArray(now))
        }

        val series = cfg.optJSONArray("series") ?: JSONArray()
        for (i in 0 until series.length()) {
            if (Thread.currentThread().isInterrupted) return
            val s = series.getJSONObject(i)
            val id = s.optString("id")
            val tv = tmdb(key, "/$id?language=sv-SE") ?: continue
            val aired = tv.optJSONObject("last_episode_to_air")?.optInt("season_number", 0) ?: 0
            val known = s.optInt("seasons", aired)
            if (aired > known) {
                val nid = "season|$id|$aired"
                if (notified.add(nid)) notify(ctx, nid.hashCode(),
                    "${s.optString("title")}: säsong $aired har kommit", "Tryck för att öppna Nytt att se.")
                s.put("seasons", aired)
            }
        }

        val keep = notified.toList().takeLast(300)
        prefs.edit().putString("cfg", cfg.toString()).putString("notified", JSONArray(keep).toString()).apply()
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun tmdb(key: String, path: String): JSONObject? {
        val bearer = key.length > 40
        val sep = if (path.contains("?")) "&" else "?"
        val url = URL("https://api.themoviedb.org/3$path" +
            if (bearer) "" else "${sep}api_key=${URLEncoder.encode(key, "UTF-8")}")
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bevakning", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
