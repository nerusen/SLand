package io.github.odlaver.tepi

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/*
 * Port of wallet/src/lib/finance.ts + the date half of format.ts.
 *
 * Contract kept identical to the web app:
 *  - every money boundary passes through rupiah() (round-half-up to whole Rupiah)
 *  - dates are plain ISO 'YYYY-MM-DD' strings anchored at UTC noon, so DST and
 *    timezone drift can never move a date by one day
 *  - splits round slices 1..n-1 and let the LAST slice absorb the remainder, so
 *    sum(slices) == rupiah(total) exactly — no Rupiah created or destroyed
 */

// ── Money primitives ──────────────────────────────────────────────────────────

/** Whole Rupiah. Mirrors `toRupiah()`; non-finite input collapses to 0. */
fun rupiah(value: Double): Long =
    if (value.isNaN() || value.isInfinite()) 0L else Math.round(value)

fun sumOf(values: List<Long>): Long = values.fold(0L) { a, n -> a + n }

// ── Date primitives (UTC-noon anchored, mirrors format.ts) ───────────────────

private val WIB: ZoneId = ZoneId.of("Asia/Jakarta")

val HARI_ID = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")

val BULAN_ID = listOf(
    "Januari", "Februari", "Maret", "April", "Mei", "Juni",
    "Juli", "Agustus", "September", "Oktober", "November", "Desember",
)

private val BULAN_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
    "Jul", "Agu", "Sep", "Okt", "Nov", "Des",
)

/** Today's date in WIB as 'YYYY-MM-DD'. Mirrors `todayWIB()`. */
fun todayWIB(): String = ZonedDateTime.now(WIB).toLocalDate().toString()

/** Current WIB wall clock as 'HH:mm'. Mirrors `timeWIB()`. */
fun timeWIB(): String {
    val now = ZonedDateTime.now(WIB)
    return "%02d:%02d".format(now.hour, now.minute)
}

/** Lenient ISO parse; anything unparseable falls back to the epoch date. */
fun parseISO(iso: String): LocalDate =
    try {
        val p = iso.split("-")
        LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt())
    } catch (_: Exception) {
        LocalDate.of(1970, 1, 1)
    }

/** ISO day-of-week, Monday = 1 … Sunday = 7. */
fun isoDayOfWeek(iso: String): Int = parseISO(iso).dayOfWeek.value

fun hariIndonesia(iso: String): String = HARI_ID[isoDayOfWeek(iso) - 1]

fun bulanIndonesia(iso: String): String = BULAN_ID[parseISO(iso).monthValue - 1]

/** Week-of-month ordinal 1..5. Mirrors the SQL `id_minggu_ke()`. */
fun mingguKe(iso: String): Int = floor((parseISO(iso).dayOfMonth - 1) / 7.0).toInt() + 1

/** `'2026-11-13'` → `"13 Nov 2026"`. */
fun formatDateShort(iso: String): String {
    val d = parseISO(iso)
    return "${d.dayOfMonth} ${BULAN_SHORT[d.monthValue - 1]} ${d.year}"
}

/** `'2026-11-13'` → `"Jumat, 13 November 2026"`. */
fun formatDateLong(iso: String): String {
    val d = parseISO(iso)
    return "${hariIndonesia(iso)}, ${d.dayOfMonth} ${BULAN_ID[d.monthValue - 1]} ${d.year}"
}

/** `'2026-11'` → `"November 2026"`. */
fun formatMonthKey(monthKey: String): String {
    val p = monthKey.split("-")
    val y = p.getOrNull(0)?.toIntOrNull() ?: 1970
    val m = p.getOrNull(1)?.toIntOrNull() ?: 1
    return "${BULAN_ID[(m - 1).coerceIn(0, 11)]} $y"
}

fun monthKeyOf(iso: String): String {
    val d = parseISO(iso)
    return "%04d-%02d".format(d.year, d.monthValue)
}

fun weekKeyOf(iso: String): String = "${monthKeyOf(iso)}-W${mingguKe(iso)}"

fun addDays(iso: String, days: Int): String = parseISO(iso).plusDays(days.toLong()).toString()

/** Monday of the ISO week containing [iso]. */
fun startOfWeek(iso: String): String = addDays(iso, -(isoDayOfWeek(iso) - 1))

/** Sunday of the ISO week containing [iso]. */
fun endOfWeek(iso: String): String = addDays(startOfWeek(iso), 6)

fun startOfMonth(iso: String): String = parseISO(iso).withDayOfMonth(1).toString()

fun endOfMonth(iso: String): String {
    val d = parseISO(iso)
    return d.withDayOfMonth(d.lengthOfMonth()).toString()
}

fun firstOfNextMonth(iso: String): String =
    parseISO(iso).withDayOfMonth(1).plusMonths(1).toString()

/** Whole days from [a] to [b]; negative when [b] precedes [a]. */
fun daysBetween(a: String, b: String): Int =
    ChronoUnit.DAYS.between(parseISO(a), parseISO(b)).toInt()

fun previousMonthKey(monthKey: String): String {
    val p = monthKey.split("-")
    val y = p.getOrNull(0)?.toIntOrNull() ?: 1970
    val m = p.getOrNull(1)?.toIntOrNull() ?: 1
    return if (m == 1) "${y - 1}-12" else "%04d-%02d".format(y, m - 1)
}

// ── Formatting ────────────────────────────────────────────────────────────────

/** `1234567` → `"Rp1.234.567"`. Indonesian grouping, no decimals. */
fun formatRupiah(value: Long): String {
    val neg = value < 0
    val digits = abs(value).toString()
    val sb = StringBuilder()
    for ((i, ch) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append('.')
        sb.append(ch)
    }
    return if (neg) "-Rp$sb" else "Rp$sb"
}

fun formatRupiahSigned(value: Long): String =
    if (value > 0) "+${formatRupiah(value)}" else formatRupiah(value)

private fun trimZero(n: Double): String {
    val fixed = if (n >= 10) "%.0f".format(n) else "%.1f".format(n)
    return fixed.removeSuffix(".0").removeSuffix(",0").replace('.', ',')
}

/** `1_250_000` → `"1,3jt"`. Mirrors `formatRupiahCompact()`. */
fun formatRupiahCompact(value: Long): String {
    val a = abs(value)
    val sign = if (value < 0) "-" else ""
    return when {
        a >= 1_000_000_000L -> "$sign${trimZero(a / 1_000_000_000.0)}M"
        a >= 1_000_000L -> "$sign${trimZero(a / 1_000_000.0)}jt"
        a >= 1_000L -> "$sign${trimZero(a / 1_000.0)}rb"
        else -> "$sign$a"
    }
}

/** Ratio in, percent out: `0.1234` → `"12,3%"`. */
fun formatPercent(ratio: Double, digits: Int = 1): String {
    val safe = if (ratio.isNaN() || ratio.isInfinite()) 0.0 else ratio
    return "%.${digits}f".format(safe * 100).replace('.', ',') + "%"
}

fun formatPercentSigned(ratio: Double, digits: Int = 1): String =
    if (ratio > 0) "+${formatPercent(ratio, digits)}" else formatPercent(ratio, digits)

/** Accepts `"67.500"`, `"Rp67500"`, `"67,5rb"`-free digits only. */
fun parseRupiahInput(input: String): Long {
    val digits = input.filter { it.isDigit() }
    if (digits.isEmpty()) return 0L
    return digits.toLongOrNull() ?: 0L
}

// ── Domain constants (mirrors constants.ts) ──────────────────────────────────

const val WEEKLY_BUDGET_CAP = 67_500L
const val DAILY_ALLOWANCE = WEEKLY_BUDGET_CAP / 7.0 // 9.642,857…
const val EMERGENCY_FUND_TARGET = 3_000_000L
const val CIGARETTE_CATEGORY = "Rokok (Max 12rb/3hr)"
const val CIGARETTE_MIN_DAYS_BETWEEN = 3

const val TX_INCOME_SALARY = "income_salary"
const val TX_INCOME_FREELANCE = "income_freelance"
const val TX_EXPENSE = "expense"
const val TX_TRANSFER = "transfer"

// ── Rows ──────────────────────────────────────────────────────────────────────

class Tx(
    val id: String,
    val tanggal: String,
    val txType: String,
    val kategori: String,
    val barang: String,
    val nominal: Long,
    val walletId: String?,
    val frozenFreelance: Boolean,
    val catatan: String?,
) {
    val isIncome: Boolean get() = txType == TX_INCOME_SALARY || txType == TX_INCOME_FREELANCE
    val isExpense: Boolean get() = txType == TX_EXPENSE
}

class Purse(
    val id: String,
    val name: String,
    val category: String,
    val balance: Long,
    val target: Long?,
)

// A Kanban card. Named Card, not Task: the package already has a Task for
// coursework from Kulia/Todoist/Notion, and the two have nothing in common.
class Card(
    val id: String,
    val title: String,
    val taskType: String,
    val status: String,
    val amount: Long,
    val client: String?,
    val unlockAt: String?,
    val notes: String?,
    val position: Int,
)

class Audit(
    val id: String,
    val date: String,
    val eveningDone: Boolean,
    val mondaySplitDone: Boolean,
)

// ── Salary / freelance splits ────────────────────────────────────────────────

class Slice(
    val key: String,
    val label: String,
    val ratio: Double,
    val amount: Long,
    val walletCategory: String?,
    val note: String,
)

private class Rule(
    val key: String,
    val label: String,
    val ratio: Double,
    val walletCategory: String?,
    val note: String,
)

private val SALARY_RULES = listOf(
    Rule("darurat", "Dana Darurat", 0.35, "darurat", "Prioritas pertama sampai Rp3.000.000 tercapai."),
    Rule("produktif", "Tabungan Produktif", 0.25, "produktif", "Modal alat kerja & peningkatan kapasitas."),
    Rule("investasi", "Investasi Pemula", 0.20, "investasi", "Reksa dana / emas mikro, disetor rutin."),
    Rule("operasional", "Operasional Harian", 0.15, "operasional", "Biaya hidup mingguan, dibatasi Rp67.500/minggu."),
    Rule("pengembangan", "Pengembangan Diri", 0.05, null, "Kursus, buku, lisensi. Dicatat sebagai alokasi di Operasional."),
)

/**
 * 35/25/20/15/5. Slices 1..n-1 round individually; the last is
 * `total - sum(previous)` so the slices always re-add to [total].
 */
fun splitSalary(total: Long): List<Slice> {
    val whole = rupiah(total.toDouble())
    var allocated = 0L
    return SALARY_RULES.mapIndexed { i, r ->
        val last = i == SALARY_RULES.size - 1
        val amount = if (last) whole - allocated else rupiah(whole * r.ratio)
        allocated += amount
        Slice(r.key, r.label, r.ratio, amount, r.walletCategory, r.note)
    }
}

class FreelanceSplit(
    val total: Long,
    val tabunganInvestasi: Long,
    val tabunganProduktif: Long,
    val investasiPemula: Long,
    val selfReward: Long,
)

/** 70% Tabungan/Investasi : 30% Self-Reward, exact to the Rupiah. */
fun splitFreelance(total: Long): FreelanceSplit {
    val whole = rupiah(total.toDouble())
    val tabunganInvestasi = rupiah(whole * 0.7)
    val selfReward = whole - tabunganInvestasi // absorbs remainder
    val tabunganProduktif = rupiah(tabunganInvestasi / 2.0)
    val investasiPemula = tabunganInvestasi - tabunganProduktif
    return FreelanceSplit(whole, tabunganInvestasi, tabunganProduktif, investasiPemula, selfReward)
}

// ── Daily / weekly summaries ─────────────────────────────────────────────────

class DaySummary(
    val date: String,
    val income: Long,
    val incomeSalary: Long,
    val incomeFreelance: Long,
    val expense: Long,
    val net: Long,
    val remainingAllowance: Long,
    val txCount: Int,
)

fun summarizeDay(transactions: List<Tx>, iso: String): DaySummary {
    val rows = transactions.filter { it.tanggal == iso }
    val incomeSalary = sumOf(rows.filter { it.txType == TX_INCOME_SALARY }.map { it.nominal })
    val incomeFreelance = sumOf(rows.filter { it.txType == TX_INCOME_FREELANCE }.map { it.nominal })
    val income = incomeSalary + incomeFreelance
    val expense = sumOf(rows.filter { it.isExpense }.map { it.nominal })
    return DaySummary(
        date = iso,
        income = income,
        incomeSalary = incomeSalary,
        incomeFreelance = incomeFreelance,
        expense = expense,
        net = rupiah((income - expense).toDouble()),
        remainingAllowance = rupiah(DAILY_ALLOWANCE - expense),
        txCount = rows.size,
    )
}

/** "aman" | "waspada" | "lewat" */
fun budgetStatus(usageRatio: Double): String = when {
    usageRatio > 1.0 -> "lewat"
    usageRatio >= 0.8 -> "waspada"
    else -> "aman"
}

fun budgetStatusLabel(status: String): String = when (status) {
    "lewat" -> "Lewat Batas"
    "waspada" -> "Waspada"
    else -> "Aman"
}

class WeekSummary(
    val weekKey: String,
    val startDate: String,
    val endDate: String,
    val income: Long,
    val expense: Long,
    val net: Long,
    val cap: Long,
    val usageRatio: Double,
    val remaining: Long,
    val status: String,
    val cigarettePacks: Int,
    val cigaretteSpend: Long,
    val txCount: Int,
)

fun summarizeWeek(transactions: List<Tx>, anchorISO: String): WeekSummary {
    val startDate = startOfWeek(anchorISO)
    val endDate = endOfWeek(anchorISO)
    val rows = transactions.filter { it.tanggal >= startDate && it.tanggal <= endDate }
    val income = sumOf(rows.filter { it.isIncome }.map { it.nominal })
    val expense = sumOf(rows.filter { it.isExpense }.map { it.nominal })
    val cig = rows.filter { it.isExpense && it.kategori == CIGARETTE_CATEGORY }
    val usageRatio = if (WEEKLY_BUDGET_CAP > 0) expense.toDouble() / WEEKLY_BUDGET_CAP else 0.0
    return WeekSummary(
        weekKey = weekKeyOf(anchorISO),
        startDate = startDate,
        endDate = endDate,
        income = income,
        expense = expense,
        net = rupiah((income - expense).toDouble()),
        cap = WEEKLY_BUDGET_CAP,
        usageRatio = usageRatio,
        remaining = rupiah((WEEKLY_BUDGET_CAP - expense).toDouble()),
        status = budgetStatus(usageRatio),
        cigarettePacks = cig.size,
        cigaretteSpend = sumOf(cig.map { it.nominal }),
        txCount = rows.size,
    )
}

// ── Cigarette quota guard (rule R04) ─────────────────────────────────────────

class CigaretteGuard(
    val blocked: Boolean,
    val lastPurchaseDate: String?,
    val daysSince: Int?,
    val daysRemaining: Int,
    val nextAllowedDate: String?,
    val packsThisWeek: Int,
    val spendThisWeek: Long,
    val message: String,
)

fun evaluateCigaretteGuard(transactions: List<Tx>, todayISO: String = todayWIB()): CigaretteGuard {
    val packs = transactions
        .filter { it.isExpense && it.kategori == CIGARETTE_CATEGORY }
        .sortedByDescending { it.tanggal }
    val week = summarizeWeek(transactions, todayISO)
    val last = packs.firstOrNull()

    if (last == null) {
        return CigaretteGuard(
            blocked = false,
            lastPurchaseDate = null,
            daysSince = null,
            daysRemaining = 0,
            nextAllowedDate = null,
            packsThisWeek = week.cigarettePacks,
            spendThisWeek = week.cigaretteSpend,
            message = "Belum ada pembelian rokok tercatat. Kuota 1 bungkus / 3 hari masih utuh.",
        )
    }

    val daysSince = daysBetween(last.tanggal, todayISO)
    val blocked = daysSince < CIGARETTE_MIN_DAYS_BETWEEN
    val daysRemaining = if (blocked) CIGARETTE_MIN_DAYS_BETWEEN - daysSince else 0
    val nextAllowed = addDays(last.tanggal, CIGARETTE_MIN_DAYS_BETWEEN)
    val shortDate = formatDateShort(last.tanggal)

    return CigaretteGuard(
        blocked = blocked,
        lastPurchaseDate = last.tanggal,
        daysSince = daysSince,
        daysRemaining = daysRemaining,
        nextAllowedDate = nextAllowed,
        packsThisWeek = week.cigarettePacks,
        spendThisWeek = week.cigaretteSpend,
        message = if (blocked) {
            "Pembelian terakhir $shortDate ($daysSince hari lalu). Aturan 1 bungkus / 3 hari " +
                "belum terpenuhi — tunggu $daysRemaining hari lagi, boleh beli lagi " +
                "${formatDateShort(nextAllowed)}."
        } else {
            "Pembelian terakhir $shortDate ($daysSince hari lalu). " +
                "Kuota 1 bungkus / 3 hari sudah terpenuhi."
        },
    )
}

// ── Discipline streak (rule R01) ─────────────────────────────────────────────

class Streak(
    val current: Int,
    val longest: Int,
    val totalAudited: Int,
    val todayDone: Boolean,
    val lastAuditDate: String?,
)

/**
 * Consecutive-day run of completed evening audits counted backwards from today.
 * When today is not yet audited the run is measured from yesterday, so an
 * unfinished evening does not falsely zero the counter before 21:00.
 */
fun computeStreak(audits: List<Audit>, todayISO: String = todayWIB()): Streak {
    val done = audits.filter { it.eveningDone }.map { it.date }.distinct().sorted()
    if (done.isEmpty()) return Streak(0, 0, 0, false, null)

    val todayDone = done.contains(todayISO)
    var anchor = if (todayDone) todayISO else addDays(todayISO, -1)
    val set = done.toHashSet()

    var current = 0
    while (set.contains(anchor)) {
        current++
        anchor = addDays(anchor, -1)
    }

    var longest = 1
    var run = 1
    for (i in 1 until done.size) {
        if (daysBetween(done[i - 1], done[i]) == 1) run++ else run = 1
        if (run > longest) longest = run
    }

    return Streak(
        current = current,
        longest = maxOf(longest, current),
        totalAudited = done.size,
        todayDone = todayDone,
        lastAuditDate = done.last(),
    )
}

// ── Dashboard KPIs ───────────────────────────────────────────────────────────

class Kpis(
    val salaryTotal: Long,
    val salaryGrowth: Double,
    val freelanceTotal: Long,
    val freelanceFrozen: Long,
    val freelanceUnlockDate: String,
    val expenseTotal: Long,
    val week: WeekSummary,
    val today: DaySummary,
    val totalBalance: Long,
    val emergencyBalance: Long,
    val emergencyTarget: Long,
    val emergencyProgress: Double,
)

fun computeKpis(
    transactions: List<Tx>,
    wallets: List<Purse>,
    todayISO: String = todayWIB(),
): Kpis {
    val salaryRows = transactions.filter { it.txType == TX_INCOME_SALARY }
    val freelanceRows = transactions.filter { it.txType == TX_INCOME_FREELANCE }

    val thisMonth = monthKeyOf(todayISO)
    val prevMonth = previousMonthKey(thisMonth)
    val salaryThisMonth = sumOf(salaryRows.filter { monthKeyOf(it.tanggal) == thisMonth }.map { it.nominal })
    val salaryPrevMonth = sumOf(salaryRows.filter { monthKeyOf(it.tanggal) == prevMonth }.map { it.nominal })

    val emergencyWallet = wallets.firstOrNull { it.category == "darurat" }
    val emergencyBalance = rupiah((emergencyWallet?.balance ?: 0L).toDouble())
    val emergencyTarget = rupiah((emergencyWallet?.target ?: EMERGENCY_FUND_TARGET).toDouble())

    return Kpis(
        salaryTotal = sumOf(salaryRows.map { it.nominal }),
        salaryGrowth = if (salaryPrevMonth > 0)
            (salaryThisMonth - salaryPrevMonth).toDouble() / salaryPrevMonth else 0.0,
        freelanceTotal = sumOf(freelanceRows.map { it.nominal }),
        freelanceFrozen = sumOf(freelanceRows.filter { it.frozenFreelance }.map { it.nominal }),
        freelanceUnlockDate = firstOfNextMonth(todayISO),
        expenseTotal = sumOf(transactions.filter { it.isExpense }.map { it.nominal }),
        week = summarizeWeek(transactions, todayISO),
        today = summarizeDay(transactions, todayISO),
        totalBalance = sumOf(wallets.map { it.balance }),
        emergencyBalance = emergencyBalance,
        emergencyTarget = emergencyTarget,
        emergencyProgress = if (emergencyTarget > 0)
            min(emergencyBalance.toDouble() / emergencyTarget, 1.0) else 0.0,
    )
}

// ── Kanban ───────────────────────────────────────────────────────────────────

val KANBAN_COLUMNS = listOf(
    Triple("backlog", "Backlog / Wishlist 48H", "01"),
    Triple("in_progress", "In Progress", "02"),
    Triple("review", "Review / Waiting", "03"),
    Triple("done", "Done / Lunas", "04"),
)

fun kanbanLabel(status: String): String =
    KANBAN_COLUMNS.firstOrNull { it.first == status }?.second ?: status
