package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.odlaver.tepi.Assistant
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.KANBAN_COLUMNS
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Wallet
import io.github.odlaver.tepi.budgetStatusLabel
import io.github.odlaver.tepi.formatDateShort
import io.github.odlaver.tepi.formatPercent
import io.github.odlaver.tepi.formatRupiah
import io.github.odlaver.tepi.formatRupiahCompact
import io.github.odlaver.tepi.sumOf
import io.github.odlaver.tepi.todayWIB
import io.github.odlaver.tepi.Prefs
import kotlin.math.max

/*
 * The three wallet tabs. They read the Supabase mirror and never write to it:
 * recording a transaction stays in the web app, which owns the triggers that
 * keep balances consistent.
 */

// ── Finance ───────────────────────────────────────────────────────────────────

class FinanceScene(host: Host, private val wallet: Wallet) : TabScene(host) {
    override val id = "finance"
    override val order = 12
    override val personal = true

    private fun s(id: Int) = host.ctx.getString(id)

    override fun contentHeight(w: Float): Float {
        val k = wallet.kpis ?: return -1f
        var h = ui.dp(18f) + ui.s12                      // head
        h += ui.dp(46f) + ui.s8                          // balance block
        h += ui.dp(30f) + ui.s8                          // budget bar + caption
        h += rowH * 3                                    // today, emergency, freelance
        if (wallet.guard?.blocked == true) h += ui.dp(30f)
        if (k.week.cigarettePacks > 0) h += rowH
        return h
    }

    override fun panelHeight(w: Float): Float {
        if (!wallet.configured) return ui.dp(18f) + ui.s12 + ui.dp(34f)
        if (wallet.kpis == null) return ui.dp(18f) + ui.s12 + ui.dp(34f)
        return minOf(contentHeight(w), ui.dp(260f))
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val k = wallet.kpis
        if (k == null) {
            val y = head(c, r, Icon.Purse, s(R.string.finance), null, a)
            val msg = if (!wallet.configured) s(R.string.finance_unset)
            else wallet.error ?: s(R.string.finance_loading)
            ui.text(c, msg, ui.body, r.left, ui.baseline(ui.body, y + ui.dp(10f)), r.width(), a)
            return
        }

        val rr = r
        var y = head(c, rr, Icon.Purse, s(R.string.finance), null, a)

        val balancePrefs = Prefs(host.ctx)
        val hidden = balancePrefs.financeBalanceHidden
        val blockH = ui.dp(48f)
        tmp.set(rr.left, y + ui.dp(4f), rr.right, y + blockH + ui.dp(4f))
        ui.fill(c, tmp, ui.radiusRow, ui.fill, a)
        ui.text(c, s(R.string.finance_total), ui.small, tmp.left + ui.s12,
            ui.baseline(ui.small, tmp.top + ui.dp(12f)), tmp.width() - ui.dp(52f), a)
        val balance = if (hidden) "••••••••" else formatRupiah(k.totalBalance)
        ui.text(c, balance, ui.big, tmp.left + ui.s12,
            ui.baseline(ui.big, tmp.top + ui.dp(32f)), tmp.width() - ui.dp(52f), a)

        val eyeSize = ui.dp(34f)
        val eyeRect = RectF(tmp.right - eyeSize - ui.dp(7f), tmp.top + (tmp.height() - eyeSize) / 2f,
            tmp.right - ui.dp(7f), tmp.top + (tmp.height() + eyeSize) / 2f)
        ui.icon(c, if (hidden) Icon.Eye else Icon.Hide, eyeRect.centerX(), eyeRect.centerY(), ui.dp(18f), ui.ink, a)
        hits.add(eyeRect to {
            balancePrefs.financeBalanceHidden = !hidden
            host.refresh()
        })
        y = tmp.bottom + ui.s8

        // Weekly budget: bar plus the numbers behind it
        val w = k.week
        val barH = ui.dp(6f)
        tmp.set(rr.left, y, rr.right, y + barH)
        ui.fill(c, tmp, barH / 2f, ui.fill, a)
        val frac = w.usageRatio.coerceIn(0.0, 1.0).toFloat()
        if (frac > 0f) {
            tmp.set(rr.left, y, rr.left + rr.width() * frac, y + barH)
            ui.fill(c, tmp, barH / 2f, if (w.status == "aman") ui.lead else ui.ink, a)
        }
        y += barH + ui.s4
        ui.text(c, "${formatRupiah(w.expense)} / ${formatRupiah(w.cap)}", ui.small,
            rr.left, ui.baseline(ui.small, y + ui.dp(6f)), rr.width() - ui.dp(90f), a)
        ui.textEnd(c, budgetStatusLabel(w.status), ui.small, rr.right, ui.baseline(ui.small, y + ui.dp(6f)), a)
        y += ui.dp(12f) + ui.s8

        // Today, emergency, freelance financials
        item(c, rr, y, s(R.string.finance_today),
            "${formatRupiah(k.today.expense)} · ${s(R.string.finance_left)} ${formatRupiah(k.today.remainingAllowance)}", a)
        y += rowH
        item(c, rr, y, s(R.string.finance_emergency),
            "${formatRupiah(k.emergencyBalance)} / ${formatRupiah(k.emergencyTarget)} · ${formatPercent(k.emergencyProgress)}", a)
        y += rowH
        item(c, rr, y, s(R.string.finance_freelance),
            if (k.freelanceFrozen > 0)
                "${formatRupiah(k.freelanceFrozen)} ${s(R.string.finance_frozen)} ${formatDateShort(k.freelanceUnlockDate)}"
            else formatRupiah(k.freelanceTotal), a)
        y += rowH

        if (k.week.cigarettePacks > 0) {
            item(c, rr, y, s(R.string.finance_cigarette),
                "${k.week.cigarettePacks} × · ${formatRupiah(k.week.cigaretteSpend)}", a)
            y += rowH
        }
        wallet.guard?.let { g ->
            if (g.blocked) {
                ui.text(c, g.message, ui.small, rr.left, ui.baseline(ui.small, y + ui.dp(10f)), rr.width(), a)
            }
        }

    }
}

// ── Kanban ────────────────────────────────────────────────────────────────────

class KanbanScene(host: Host, private val wallet: Wallet) : TabScene(host) {
    override val id = "kanban"
    override val order = 13
    override val personal = true

    private fun s(id: Int) = host.ctx.getString(id)

    override fun contentHeight(w: Float): Float {
        if (wallet.tasks.isEmpty()) return -1f
        var h = ui.dp(18f) + ui.s12
        for ((status, _, _) in KANBAN_COLUMNS) {
            val rows = wallet.column(status)
            if (rows.isEmpty()) continue
            h += ui.dp(16f)                 // column caption
            h += rowH * minOf(rows.size, PER_COLUMN)
        }
        return h
    }

    override fun panelHeight(w: Float): Float {
        if (wallet.tasks.isEmpty()) return ui.dp(18f) + ui.s12 + ui.dp(34f)
        return minOf(contentHeight(w), ui.dp(260f))
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        if (wallet.tasks.isEmpty()) {
            val y = head(c, r, Icon.Kanban, s(R.string.kanban), null, a)
            val msg = if (!wallet.configured) s(R.string.finance_unset)
            else wallet.error ?: s(R.string.kanban_empty)
            ui.text(c, msg, ui.body, r.left, ui.baseline(ui.body, y + ui.dp(10f)), r.width(), a)
            return
        }

        val locked = wallet.lockedWishlist().size
        val rr = r
        var y = head(c, rr, Icon.Kanban, s(R.string.kanban),
            if (locked > 0) "$locked ${s(R.string.kanban_locked)}" else "${wallet.tasks.size}", a)

        for ((status, label, _) in KANBAN_COLUMNS) {
            val rows = wallet.column(status)
            if (rows.isEmpty()) continue
            ui.text(c, "$label · ${rows.size}", ui.small, rr.left, ui.baseline(ui.small, y + ui.dp(8f)),
                rr.width() - ui.dp(70f), a)
            ui.textEnd(c, formatRupiahCompact(sumOf(rows.map { it.amount })), ui.small,
                rr.right, ui.baseline(ui.small, y + ui.dp(8f)), a)
            y += ui.dp(16f)
            for (t in rows.take(PER_COLUMN)) {
                val unlock = t.unlockAt?.take(10)
                val detail = buildString {
                    append(formatRupiah(t.amount))
                    if (t.client != null) append(" · ${t.client}")
                    if (unlock != null && unlock > todayWIB()) {
                        append(" · ${s(R.string.kanban_until)} ${formatDateShort(unlock)}")
                    }
                }
                item(c, rr, y, t.title, detail, a)
                y += rowH
            }
        }
    }

    private companion object { const val PER_COLUMN = 4 }
}

// ── Assistant ─────────────────────────────────────────────────────────────────

class AssistantScene(
    host: Host,
    private val wallet: Wallet,
    private val ai: Assistant,
) : TabScene(host) {
    override val id = "assistant"
    override val order = 14
    override val personal = true

    private fun s(id: Int) = host.ctx.getString(id)

    private val shown: List<Assistant.Turn>
        get() = ai.history.takeLast(TURNS)

    // One layout per turn, rebuilt only when the text or the width changes
    private val layouts = HashMap<String, StaticLayout>()
    private var laidOutAt = 0f

    private fun layout(text: String, mine: Boolean, w: Float): StaticLayout {
        if (laidOutAt != w) { layouts.clear(); laidOutAt = w }
        return layouts.getOrPut(text) {
            val p = TextPaint(if (mine) ui.body else ui.bodyInk)
            StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, w.toInt()))
                .setMaxLines(MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }
    }

    override fun contentHeight(w: Float): Float {
        if (shown.isEmpty()) return -1f
        var h = ui.dp(18f) + ui.s12
        for (t in shown) h += ui.dp(14f) + layout(t.text, t.role == "user", w).height
        h += ui.btnH + ui.s8 + ui.s4
        return h
    }

    override fun panelHeight(w: Float): Float {
        if (!ai.configured) return ui.dp(18f) + ui.s12 + ui.dp(34f)
        if (shown.isEmpty()) return ui.dp(18f) + ui.s12 + ui.dp(20f) + ui.btnH + ui.s8
        return minOf(contentHeight(w), ui.dp(260f))
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val end: String? = if (ai.busy) s(R.string.assistant_thinking) else Assistant.MODEL_LABELS[ai.model()]

        if (!ai.configured) {
            val y = head(c, r, Icon.Spark, s(R.string.assistant), null, a)
            ui.text(c, s(R.string.assistant_unset), ui.body, r.left, ui.baseline(ui.body, y + ui.dp(10f)), r.width(), a)
            return
        }

        if (shown.isEmpty()) {
            var y = head(c, r, Icon.Spark, s(R.string.assistant), end, a)
            ui.text(c, ai.lastError ?: s(R.string.assistant_hint), ui.body, r.left,
                ui.baseline(ui.body, y + ui.dp(8f)), r.width(), a)
            y += ui.dp(20f)
            composer(c, r, y, a)
            return
        }

        val rr = r
        var y = head(c, rr, Icon.Spark, s(R.string.assistant), end, a)
        for (t: Assistant.Turn in shown) {
            val mine = t.role == "user"
            ui.text(c, if (mine) s(R.string.assistant_you) else s(R.string.assistant_name),
                ui.small, rr.left, ui.baseline(ui.small, y + ui.dp(7f)), rr.width(), a)
            y += ui.dp(14f)
            val l = layout(t.text, mine, rr.width())
            c.save()
            c.translate(rr.left, y)
            l.paint.alpha = (255 * a).toInt()
            l.draw(c)
            c.restore()
            y += l.height
        }
        composer(c, rr, y + ui.s4, a)
    }

    private fun composer(c: Canvas, r: RectF, y: Float, a: Float) {
        val half = (r.width() - ui.s8) / 2f
        tmp.set(r.left, y, r.left + half, y + ui.btnH)
        ui.button(c, tmp, s(R.string.assistant_ask), a, primary = true, icon = Icon.Spark)
        hits.add(RectF(tmp) to { ask() })

        tmp.set(r.left + half + ui.s8, y, r.right, y + ui.btnH)
        ui.button(c, tmp, s(R.string.assistant_clear), a)
        hits.add(RectF(tmp) to { ai.clear(); resetScroll() })
    }

    private fun ask() {
        if (ai.busy) return
        host.input(host.ctx.getString(R.string.assistant_ask)) { text: String ->
            ai.ask(text, wallet) { _answer: String?, _error: String? ->
                resetScroll()
                host.refresh()
            }
            host.refresh()
        }
    }

    private companion object {
        const val TURNS = 6
        const val MAX_LINES = 6
    }
}
