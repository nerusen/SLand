package io.github.odlaver.tepi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

// One square of the calendar. Level is GitHub's own 0..4 bucket, so the shades
// match the ones on the profile page rather than being re-derived here.
class GhDay(val date: String, val count: Int, val level: Int, val weekday: Int)

// The contribution calendar of whoever owns the token, refreshed in the
// background. The token is sealed in the Vault like the other integrations.
class Github(context: Context, private val vault: Vault, private val changed: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Columns, oldest first, each a Sunday-to-Saturday week as GitHub sends it
    @Volatile var weeks: List<List<GhDay>> = emptyList()
        private set
    @Volatile var total = 0
        private set
    @Volatile var login = ""
        private set
    @Volatile var failed = false
        private set
    @Volatile var loaded = false
        private set

    // Read on every frame the tab is drawn; the Vault decrypts on each get,
    // so whether a token exists is kept here instead
    @Volatile var linked = vault.get(KEY) != null
        private set

    private val loop = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, if (failed) 120_000L else 1_800_000L)
        }
    }

    fun start() { main.removeCallbacks(loop); main.post(loop) }
    fun stop() { main.removeCallbacks(loop) }

    fun save(token: String?) {
        vault.put(KEY, token)
        linked = !token.isNullOrEmpty()
        if (!linked) { weeks = emptyList(); total = 0; login = ""; loaded = false; failed = false; main.post { changed() } }
        else refresh()
    }

    fun refresh() {
        val tok = vault.get(KEY) ?: return
        worker.execute {
            val got = fetch(tok)
            main.post {
                if (got == null) failed = true
                else {
                    weeks = got.weeks; total = got.total; login = got.login
                    failed = false; loaded = true
                }
                changed()
            }
        }
    }

    class Calendar(val login: String, val total: Int, val weeks: List<List<GhDay>>)

    companion object {
        private const val KEY = "github"
        private const val QUERY = "query { viewer { login contributionsCollection { contributionCalendar { totalContributions " +
            "weeks { contributionDays { date contributionCount contributionLevel weekday } } } } } }"

        // GraphQL is the only API that returns the calendar itself; the REST
        // events feed has no counts per day
        fun fetch(token: String): Calendar? {
            val body = JSONObject().put("query", QUERY).toString()
            val r = Net.send("POST", "https://api.github.com/graphql",
                mapOf("Authorization" to "bearer $token", "Content-Type" to "application/json"), body)
            if (!r.ok) return null
            return try {
                val root = JSONObject(r.body)
                if (root.has("errors")) return null
                val viewer = root.getJSONObject("data").getJSONObject("viewer")
                val cal = viewer.getJSONObject("contributionsCollection").getJSONObject("contributionCalendar")
                val arr = cal.getJSONArray("weeks")
                val weeks = ArrayList<List<GhDay>>(arr.length())
                for (i in 0 until arr.length()) {
                    val days = arr.getJSONObject(i).getJSONArray("contributionDays")
                    val col = ArrayList<GhDay>(days.length())
                    for (j in 0 until days.length()) {
                        val d = days.getJSONObject(j)
                        col.add(GhDay(d.getString("date"), d.optInt("contributionCount"), level(d.optString("contributionLevel")), d.optInt("weekday")))
                    }
                    weeks.add(col)
                }
                Calendar(viewer.optString("login"), cal.optInt("totalContributions"), weeks)
            } catch (_: Exception) { null }
        }

        private fun level(s: String) = when (s) {
            "FIRST_QUARTILE" -> 1
            "SECOND_QUARTILE" -> 2
            "THIRD_QUARTILE" -> 3
            "FOURTH_QUARTILE" -> 4
            else -> 0
        }
    }
}
