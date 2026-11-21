package io.github.odlaver.tepi

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.Executors

/*
 * Read-only mirror of the Supabase wallet database.
 *
 * The phone never writes: every mutation stays in the web app, which owns the
 * triggers that keep balances and the calendar columns consistent. Here we only
 * pull rows through the REST endpoint with the anon key and recompute the same
 * derived numbers locally via Money.kt.
 */
class Wallet(private val vault: Vault, private val changed: () -> Unit) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var transactions: List<Tx> = emptyList(); private set
    @Volatile var wallets: List<Purse> = emptyList(); private set
    @Volatile var tasks: List<Card> = emptyList(); private set
    @Volatile var audits: List<Audit> = emptyList(); private set

    @Volatile var kpis: Kpis? = null; private set
    @Volatile var guard: CigaretteGuard? = null; private set
    @Volatile var streak: Streak? = null; private set

    @Volatile var loading = false; private set
    @Volatile var error: String? = null; private set
    @Volatile var fetchedAt = 0L; private set

    private var running = false

    val configured: Boolean
        get() = !url().isNullOrBlank() && !anonKey().isNullOrBlank()

    private fun url(): String? = vault.get(KEY_URL)?.trimEnd('/')
    private fun anonKey(): String? = vault.get(KEY_ANON)

    fun configure(baseUrl: String?, anon: String?) {
        vault.put(KEY_URL, baseUrl?.trim())
        vault.put(KEY_ANON, anon?.trim())
        transactions = emptyList(); wallets = emptyList()
        tasks = emptyList(); audits = emptyList()
        kpis = null; guard = null; streak = null
        error = null; fetchedAt = 0L
    }

    fun start() {
        if (running) return
        running = true
        tick()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        if (!running) return
        refresh()
        main.postDelayed({ tick() }, POLL_MS)
    }

    /** Fetch all four tables off the main thread, then recompute derived state. */
    fun refresh() {
        if (loading || !configured) return
        loading = true
        io.execute {
            val base = url()
            val key = anonKey()
            if (base == null || key == null) {
                finish("Supabase belum dikonfigurasi.")
                return@execute
            }
            val headers = mapOf(
                "apikey" to key,
                "Authorization" to "Bearer $key",
                "Accept" to "application/json",
            )
            try {
                val txRows = fetch(base, key, headers, "transactions", "tanggal.desc", 2000)
                val walletRows = fetch(base, key, headers, "wallets", "created_at.asc", 50)
                val taskRows = fetch(base, key, headers, "kanban_tasks", "position.asc", 500)
                val auditRows = fetch(base, key, headers, "daily_audits", "audit_date.desc", 400)

                val tx = (0 until txRows.length()).mapNotNull { i ->
                    txRows.optJSONObject(i)?.let { o ->
                        Tx(
                            id = o.optString("id"),
                            tanggal = o.optString("tanggal"),
                            txType = o.optString("tx_type"),
                            kategori = o.optString("kategori"),
                            barang = o.optString("barang_keperluan"),
                            nominal = o.optLong("nominal"),
                            walletId = o.optString("wallet_id").ifBlank { null },
                            frozenFreelance = o.optBoolean("is_frozen_freelance"),
                            catatan = o.optString("catatan").ifBlank { null },
                        )
                    }
                }
                val purses = (0 until walletRows.length()).mapNotNull { i ->
                    walletRows.optJSONObject(i)?.let { o ->
                        Purse(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            category = o.optString("category"),
                            balance = o.optLong("balance"),
                            target = if (o.isNull("target_amount")) null else o.optLong("target_amount"),
                        )
                    }
                }
                val kanban = (0 until taskRows.length()).mapNotNull { i ->
                    taskRows.optJSONObject(i)?.let { o ->
                        Card(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            taskType = o.optString("task_type"),
                            status = o.optString("status"),
                            amount = o.optLong("amount"),
                            client = o.optString("client_or_store").ifBlank { null },
                            unlockAt = o.optString("unlock_at").ifBlank { null },
                            notes = o.optString("notes").ifBlank { null },
                            position = o.optInt("position"),
                        )
                    }
                }
                val auditList = (0 until auditRows.length()).mapNotNull { i ->
                    auditRows.optJSONObject(i)?.let { o ->
                        Audit(
                            id = o.optString("id"),
                            date = o.optString("audit_date"),
                            eveningDone = o.optBoolean("evening_audit_done"),
                            mondaySplitDone = o.optBoolean("monday_split_done"),
                        )
                    }
                }

                val today = todayWIB()
                transactions = tx
                wallets = purses
                tasks = kanban
                audits = auditList
                kpis = computeKpis(tx, purses, today)
                guard = evaluateCigaretteGuard(tx, today)
                streak = computeStreak(auditList, today)
                fetchedAt = System.currentTimeMillis()
                finish(null)
            } catch (e: Exception) {
                finish(e.message ?: "Gagal memuat data dompet.")
            }
        }
    }

    /** One-shot connectivity probe; reports a human-readable result. */
    fun testConnection(done: (Boolean, String) -> Unit) {
        val base = url()
        val key = anonKey()
        if (base.isNullOrBlank() || key.isNullOrBlank()) {
            done(false, "URL atau anon key belum diisi.")
            return
        }
        io.execute {
            val reply = Net.send(
                "GET",
                "$base/rest/v1/wallets?select=id&limit=1",
                mapOf("apikey" to key, "Authorization" to "Bearer $key", "Accept" to "application/json"),
            )
            val ok = reply.ok
            val msg = when {
                ok -> "Tersambung ke Supabase."
                reply.status == 401 || reply.status == 403 -> "Anon key ditolak (${reply.status})."
                reply.status == 404 -> "Tabel tidak ditemukan — cek URL proyek."
                reply.status == 0 -> "Tidak ada jaringan atau URL bukan https."
                else -> "Supabase menolak permintaan (${reply.status})."
            }
            main.post { done(ok, msg) }
        }
    }

    private fun fetch(
        base: String,
        key: String,
        headers: Map<String, String>,
        table: String,
        order: String,
        limit: Int,
    ): JSONArray {
        val q = "select=*&order=${enc(order)}&limit=$limit"
        val reply = Net.send("GET", "$base/rest/v1/$table?$q", headers)
        if (!reply.ok) {
            throw IllegalStateException(
                when (reply.status) {
                    0 -> "Tidak ada jaringan."
                    401, 403 -> "Anon key ditolak."
                    404 -> "Tabel $table tidak ditemukan."
                    else -> "Gagal memuat $table (${reply.status})."
                }
            )
        }
        return try { JSONArray(reply.body) } catch (_: Exception) { JSONArray() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun finish(err: String?) {
        loading = false
        error = err
        main.post { changed() }
    }

    /** Freelance income still frozen until the first of next month. */
    fun frozenFreelance(): Long = kpis?.freelanceFrozen ?: 0L

    /** Tasks in one Kanban column, already ordered by position. */
    fun column(status: String): List<Card> =
        tasks.filter { it.status == status }.sortedBy { it.position }

    /** Wishlist items whose 48-hour cooling-off window has not elapsed. */
    fun lockedWishlist(todayISO: String = todayWIB()): List<Card> =
        tasks.filter { t ->
            t.taskType == "wishlist_48h" && t.unlockAt != null &&
                t.unlockAt.substring(0, minOf(10, t.unlockAt.length)) > todayISO
        }

    companion object {
        const val KEY_URL = "wallet.url"
        const val KEY_ANON = "wallet.anon"
        private const val POLL_MS = 5L * 60L * 1000L
    }
}
