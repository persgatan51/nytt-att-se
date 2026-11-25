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
 *  - nya titlar med personer du följer som finns på dina tjänster
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

        // Sparas i finally, så att skickade notiser och nya tjänster/säsonger inte glöms om
        // systemet avbryter jobbet halvvägs (annars kommer samma notis igen nästa gång).
        // Vad som redan är känt per följd person sparas också separat: webbappen skickar sin egen
        // "known"-lista vid varje setWatch, och då ska det som upptäckts här inte glömmas.
        val pknown = try { JSONObject(prefs.getString("pknown", "{}")) } catch (_: Exception) { JSONObject() }
        val prov = ProvCache(ctx)
        try {
            checkAll(ctx, cfg, key, services, notified, pknown, prov)
        } finally {
            val keep = notified.toList().takeLast(300)
            prefs.edit().putString("cfg", cfg.toString()).putString("notified", JSONArray(keep).toString())
                .putString("pknown", pknown.toString()).commit()
            prov.prune()
        }
    }

    private fun checkAll(ctx: Context, cfg: JSONObject, key: String, services: JSONArray, notified: LinkedHashSet<String>,
                         pknown: JSONObject, prov: ProvCache) {
        val want = cfg.optJSONArray("want") ?: JSONArray()
        for (i in 0 until want.length()) {
            if (Thread.currentThread().isInterrupted) return
            val w = want.getJSONObject(i)
            val id = w.optString("id")
            val before = w.optJSONArray("svcs").strings()
            val avail = servicesFor(key, id, services) ?: continue
            val now = avail.map { it.first }
            val newLabels = avail.filter { it.first !in before }.map { it.second }
            val added = now.filter { it !in before }
            if (added.isNotEmpty()) {
                val nid = "avail|$id|" + added.joinToString(",")
                if (notified.add(nid)) notify(ctx, nid.hashCode(),
                    "${w.optString("title")} finns nu på ${newLabels.joinToString(" och ")}",
                    "Från din Vill se-lista. Tryck för att öppna titeln.", id)
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
                    "${s.optString("title")}: säsong $aired har kommit", "Tryck för att öppna serien.", id)
                s.put("seasons", aired)
            }
        }

        // Personer du följer: nya titlar med hen som finns på dina tjänster.
        val people = cfg.optJSONArray("people") ?: JSONArray()
        val today = java.time.LocalDate.now().toString()
        for (i in 0 until people.length()) {
            if (Thread.currentThread().isInterrupted) return
            val p = people.getJSONObject(i)
            val pid = p.optLong("id")
            if (pid <= 0) continue
            val known = (p.optJSONArray("known").strings() + pknown.optJSONArray(pid.toString()).strings()).toMutableSet()
            val res = tmdb(key, "/person/$pid/combined_credits?language=sv-SE") ?: continue
            try {
                for (c in PersonCredits.recent(res, today).filter { it.id !in known }) {
                    if (Thread.currentThread().isInterrupted) return
                    // Titlar som inte finns på dina tjänster kollas om vid varje körning – med cache
                    // (ett dygn) blir det högst ett TMDB-anrop per titel och dygn.
                    val avail = servicesFor(key, c.id, services, prov) ?: continue
                    if (avail.isEmpty()) continue
                    known += c.id
                    val nid = "person|$pid|${c.id}"
                    if (notified.add(nid)) notify(ctx, nid.hashCode(),
                        PersonCredits.text(p.optString("name"), c, avail.joinToString(" och ") { it.second }, today),
                        "Från en person du följer. Tryck för att öppna titeln.", c.id)
                }
            } finally {
                val list = JSONArray(known.toList().takeLast(200))
                p.put("known", list)
                pknown.put(pid.toString(), list)
            }
        }
        // Personer du slutat följa tas bort, så att listan inte växer i onödan.
        val following = (0 until people.length()).map { people.getJSONObject(it).optLong("id").toString() }.toSet()
        for (k in pknown.keys().asSequence().toList()) if (k !in following) pknown.remove(k)
    }

    /** Vilka av dina tjänster titeln finns på (nyckel, namn) – null om TMDB inte svarade. */
    private fun servicesFor(key: String, id: String, services: JSONArray, cache: ProvCache? = null): List<Pair<String, String>>? {
        val names = cache?.get(id) ?: run {
            val res = tmdb(key, "/$id/watch/providers") ?: return null
            val se = res.optJSONObject("results")?.optJSONObject("SE") ?: JSONObject()
            val n = mutableListOf<String>()
            for (t in listOf("flatrate", "free", "ads")) se.optJSONArray(t)?.let { a ->
                for (j in 0 until a.length()) n += a.getJSONObject(j).optString("provider_name").trim()
            }
            cache?.put(id, n)
            n
        }
        val out = mutableListOf<Pair<String, String>>()
        for (j in 0 until services.length()) {
            val s = services.getJSONObject(j)
            val re = try { Regex(s.optString("pattern"), RegexOption.IGNORE_CASE) } catch (_: Exception) { continue }
            if (names.any { re.matches(it) }) out += s.optString("key") to s.optString("label")
        }
        return out
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

    /** open = titelns TMDB-id (t.ex. "tv/1399"); ett tryck på notisen öppnar titeln i appen. */
    private fun notify(ctx: Context, id: Int, title: String, text: String, open: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bevakning", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        // Egen requestCode per notis, annars delar alla notiser samma intent (och öppnar samma titel).
        val intent = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN)
            .putExtra(MainActivity.EXTRA_OPEN, open)
        val pi = PendingIntent.getActivity(ctx, id, intent,
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

/**
 * De senaste utgivna titlarna med en person (som i webbappen): filmer och serier där hen spelar
 * (inte pratshower eller "sig själv") eller har regi, manus eller skapat serien. Nyast först.
 */
object PersonCredits {
    data class Credit(val id: String, val title: String, val date: String)

    private val crewJobs = setOf("Director", "Creator", "Screenplay", "Writer", "Showrunner")
    private val selfRole = Regex("^(self|himself|herself|themselves|sig själv)\\b", RegexOption.IGNORE_CASE)

    fun recent(res: JSONObject, today: String, max: Int = 20): List<Credit> {
        val byId = LinkedHashMap<String, Credit>()
        fun add(a: JSONArray?, ok: (JSONObject) -> Boolean) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val type = x.optString("media_type")
                if (type != "movie" && type != "tv") continue
                val genres = x.optJSONArray("genre_ids")
                if (genres != null && (0 until genres.length()).any { genres.optInt(it) == 10767 || genres.optInt(it) == 10763 }) continue
                if (!ok(x)) continue
                val date = x.optString("release_date").ifEmpty { x.optString("first_air_date") }
                if (date.isEmpty() || date > today) continue
                val id = "$type/${x.optLong("id")}"
                if (id !in byId) byId[id] = Credit(id, x.optString("title").ifEmpty { x.optString("name") }, date)
            }
        }
        add(res.optJSONArray("cast")) { !selfRole.containsMatchIn(it.optString("character").trim()) }
        add(res.optJSONArray("crew")) { it.optString("job") in crewJobs }
        return byId.values.sortedByDescending { it.date }.take(max)
    }

    /**
     * Notisens rubrik (samma text som i webbappen): "Ny med …" bara för titlar från det senaste året.
     * Äldre titlar som just kommit till en tjänst får "Nu på Netflix: Titel (2009), med …".
     */
    fun text(name: String, c: Credit, svcs: String, today: String): String {
        val yearAgo = (today.take(4).toIntOrNull()?.minus(1)?.toString() ?: "") + today.drop(4)
        return if (c.date < yearAgo) "Nu på $svcs: ${c.title} (${c.date.take(4)}), med $name"
               else "Ny med $name: ${c.title} finns på $svcs"
    }
}

/** TMDB:s svenska tjänster per titel, sparade ett dygn (för personkollen, som annars frågar om samma titlar varje gång). */
class ProvCache(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("prov", Context.MODE_PRIVATE)
    private val ttl = TimeUnit.DAYS.toMillis(1)

    fun get(id: String): List<String>? = try {
        val o = JSONObject(prefs.getString(id, null) ?: return null)
        if (System.currentTimeMillis() - o.optLong("t") > ttl) null
        else o.optJSONArray("n")?.let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { null }

    fun put(id: String, names: List<String>) {
        prefs.edit().putString(id, JSONObject().put("t", System.currentTimeMillis()).put("n", JSONArray(names)).toString()).apply()
    }

    /** Tar bort gamla poster, så att filen inte växer. */
    fun prune() {
        val now = System.currentTimeMillis()
        val e = prefs.edit()
        for ((k, v) in prefs.all) {
            val t = try { JSONObject(v as? String ?: "").optLong("t") } catch (_: Exception) { 0L }
            if (now - t > ttl) e.remove(k)
        }
        e.apply()
    }
}
