# Wallet Bridge — implementation contract

SLand 2.0.1 mirrors the `wallet` web app (wallet.nelsencreative.com) on the
island. This file is the single source of truth for every number, colour and
wire format. Ported formulas MUST match `wallet/src/lib/*` exactly — a one
rupiah drift makes both surfaces untrustworthy.

## 1. Modes

Mode is **derived, never toggled**. If `Vault` holds a Supabase URL + anon key
→ remote mode. Otherwise → local mode. One source of truth.

| | local | remote |
|---|---|---|
| Finance / kanban / notes | local store | Supabase read-only |
| AI assistant | works (needs Gemini key) | works + transcript sync |

## 2. Supabase access — READ ONLY

All six tables grant `select` to `anon` with `using (true)`; **every write is
`service_role`-only**. Never ship the service role key.

Tables: `transactions`, `wallets`, `kanban_tasks`, `daily_audits`,
`chat_messages`, `daily_notes`.

REST: `GET {url}/rest/v1/{table}?select=*` with headers
`apikey: {anon}` and `Authorization: Bearer {anon}`.

**An empty result set is an ERROR state, not zero.** If the user ever drops the
`*_anon_select_realtime` policies to harden the site, queries still succeed but
return nothing — rendering a plausible-looking `Rp 0` balance. Three explicit
states: `loading` / `error(+cache age)` / `data`.

Cache the last good JSON payload in `Prefs` so the island shows last-known
figures on boot/offline instead of an empty panel.

### Columns

```
transactions: id, tanggal(date), hari, minggu_ke, bulan, tx_type, kategori,
              barang_keperluan, nominal numeric(15,2), wallet_id,
              target_wallet_id, is_frozen_freelance, catatan, created_at
wallets:      id, name, category, balance numeric(15,2),
              target_amount numeric(15,2), accent_tag, created_at
```

`tx_type` ∈ `income_salary` | `income_freelance` | `expense` | `transfer`
`wallets.category` ∈ `darurat` | `produktif` | `investasi` | `operasional` | `freelance`

`nominal` is `numeric(15,2)` → parse as `BigDecimal`, never `Int`/`Double`.

## 3. Money + date primitives (port verbatim)

```kotlin
// finance.ts:35 — round to whole rupiah at EVERY boundary
fun toRupiah(v: Double): Long = if (!v.isFinite()) 0 else Math.round(v)
// finance.ts:40
fun sum(xs: List<Double>): Long = toRupiah(xs.filter { it.isFinite() }.sum())
// finance.ts:50
fun isExpense(tx) = tx.txType == "expense"   // transfer is NEVER income/spend
```

Dates are resolved in **Asia/Jakarta (WIB)** and parsed at **UTC noon** to kill
DST/offset drift (`format.ts:163`). Weeks are **ISO weeks, Monday→Sunday**.

```kotlin
// format.ts:163 parseISODate — UTC noon is load-bearing
Date.UTC(y, m - 1, d, 12, 0, 0)
// format.ts:176 isoDayOfWeek — Sunday(0) becomes 7
val day = utcDay(iso); if (day == 0) 7 else day
// format.ts:261 firstOfNextMonth
Date.UTC(year, month + 1, 1, 12)
```

Constants (`constants.ts`): `WEEKLY_BUDGET_CAP = 67_500`,
`DAILY_ALLOWANCE = 67_500 / 7`, `EMERGENCY_FUND_TARGET = 3_000_000`,
`CIGARETTE_CATEGORY = "Rokok (Max 12rb/3hr)"`, `CIGARETTE_MIN_DAYS_BETWEEN = 3`.

### Splits — the last slice absorbs the remainder

Never lose or invent money. `finance.ts:84` and `:113-128`:

```kotlin
// salary: iterate SALARY_SPLIT_RULES, last slice takes what's left
val amount = if (isLast) whole - allocated else toRupiah(whole * rule.ratio)

// freelance 70/30
val tabunganInvestasi = toRupiah(whole * 0.7)
val selfReward        = whole - tabunganInvestasi      // absorbs remainder
val tabunganProduktif = toRupiah(tabunganInvestasi / 2)
val investasiPemula   = tabunganInvestasi - tabunganProduktif
```

`SALARY_SPLIT_RULES`: darurat .35, produktif .25, investasi .20,
operasional .15, pengembangan .05.

Invariants to assert (README:300-305):
`splitSalary(450_000).sum() == 450_000` and
`splitFreelance(750_000).let { it.tabunganInvestasi + it.selfReward } == 750_000`.

## 4. Finance tab (order 2, `Icon.Chart`)

One tab holds both KPIs and the chart — they are the same story.

### The four columns (`finance.ts:445-484`)

All three totals are **all-time**, and net balance is the **sum of wallet
balances**, not income minus expense. This matches the web exactly.

| Column | Value | Percentage shown |
|---|---|---|
| Gaji | `sum(tx_type == income_salary)` | MoM growth |
| Freelance | `sum(tx_type == income_freelance)` | % frozen |
| Pengeluaran | `sum(isExpense)` | % of weekly cap |
| Saldo bersih | `sum(wallets.balance)` | % emergency-fund progress |

```kotlin
salaryGrowth = if (salaryPrevMonth > 0)
    (salaryThisMonth - salaryPrevMonth) / salaryPrevMonth else 0.0
freelanceFrozen  = sum(freelanceRows.filter { it.isFrozenFreelance })
emergencyBalance = toRupiah(wallets.find { it.category == "darurat" }?.balance ?: 0)
emergencyTarget  = toRupiah(wallet?.targetAmount ?: EMERGENCY_FUND_TARGET)
emergencyProgress = if (target > 0) min(balance / target, 1.0) else 0.0
```

Weekly budget status (`finance.ts:223`): `> 1.0` → `lewat`,
`>= 0.8` → `waspada`, else `aman`.

### Chart — 30 daily buckets, no toggle

`buildChartSeries(harian, days = 30)` (`finance.ts:333-389`):

1. Seed an even axis: for `i` in `29..0`, bucket key = `shiftISO(today, -i)`.
2. **Drop any transaction outside the seeded window** (`finance.ts:376`).
3. Accumulate per `tx_type` into `gaji` / `freelance` / `pengeluaran`.
   `transfer` is excluded entirely.
4. `toRupiah()` each bucket at the end.

Curve: **monotone cubic (Fritsch–Carlson)**. Plain Catmull-Rom overshoots below
zero on financial data; monotone cannot. The web defaults to `linear` with a
`monotone` toggle — the island uses monotone permanently.

## 5. Colour — the one sanctioned exception

`Ui.kt:56-88` keeps all text white across 9 themes, and `GithubTab.kt:15-19`
deliberately strips GitHub's greens so the chart "sits with the rest of the
island instead of being the one coloured thing on it".

The finance chart is the **single** exception, and it is narrow:

- Hue applies to **chart strokes and area fills only**.
- KPI numbers, labels, axes, gridlines stay `Ui.ink` / `sub` / `faint` / `line`.
- Gaji `#51CD85` (green), Pengeluaran `#EF4444` (red), Freelance `Ui.ink` (white).

Use the `tnum` paints (`Ui.big`, `compact`, `small`) for every figure so digits
don't jitter as values animate.

## 6. Kanban + Wishlist (order 9, `Icon.Kanban`)

One tab, read-only. `kanban_tasks.status` ∈ `backlog` | `in_progress` |
`review` | `done` — show all four. `task_type` ∈ `freelance_project` |
`wishlist_48h` | `career_task` is a filter within the tab, not a separate tab.

`wishlist_48h` rows carry `unlock_at`; render a live countdown. This is more
useful on the island than on the web because the island is always visible.

## 7. Notes — remote-backed, scrollable

Local `Notes.kt` is `text` + `pinned` with **no id/timestamp**. Remote
`daily_notes` has `title`, `content`, `tag`, `note_date`, `is_pinned`,
`position`, `updated_at`.

Migration: first line → `title`, remainder → `content`. Add stable ids.

## 8. Scrolling — build once, use four times

The island has **no scrolling today**; every panel is fixed-height via
`panelHeight(w)`. Notes, kanban, chat and the transaction list all need it, so
add one reusable vertical scroll helper on `TabScene`: offset + clip + fling via
the existing `Spring.kt`, with a hairline indicator in `Ui.faint`.
`Ui.panelMax` (380dp) is the natural ceiling.

## 9. AI assistant (order 10, `Icon.Chat`)

SLand calls Gemini **directly**, so the assistant works in local mode too. In
remote mode it also reads `chat_messages` for history and pushes the transcript
to the wallet's bearer endpoint to stay in sync with the web.

- **Island**: exactly 2 bubbles (last user + last assistant) plus a
  tap-to-type row that opens `Input.kt`. Do not embed an `EditText` in
  `TYPE_ACCESSIBILITY_OVERLAY` — `Input.kt` already solves IME focus over the
  overlay, which is the genuinely hard part.
- **In-app**: full transcript, same composer.
- **Optimistic send**: show the user's message immediately as pending, then
  reconcile. Without this the island feels broken for ~2s.

### Models and fallback — identical to web

`models.ts:8-58`, `generate.ts:20-21`:

```
gemini-3.8-flash       DEFAULT · MEDIUM THINKING   supportsThinking = true
gemini-flash-latest    FAST · STABLE               supportsThinking = false
gemini-3.1-flash-lite  LITE · ANTI-BUSY            supportsThinking = false
```

Default `gemini-3.8-flash`. Queue = selected, then `FALLBACK_ORDER`
(`gemini-flash-latest`, `gemini-3.1-flash-lite`), de-duplicated:

```
gemini-3.8-flash      → [3.8-flash, flash-latest, 3.1-flash-lite]
gemini-flash-latest   → [flash-latest, 3.1-flash-lite]
gemini-3.1-flash-lite → [3.1-flash-lite, flash-latest]
```

Executor: `MAX_TRIES_PER_MODEL = 2`, `RETRY_BACKOFF_MS = 800`. Per model, retry
twice on a transient error, then escalate to the next model. **An auth error
aborts the entire chain immediately** — retrying an invalid key cannot succeed
and only multiplies latency.

`thinkingConfig { thinkingLevel: MEDIUM }` is sent **only** to models with
`supportsThinking`; unknown models are treated as unsupported (safer to omit an
optional field than to send a rejected one). `temperature = 0.4`.

Endpoint (SDK-free, `HttpURLConnection` + `org.json`):
`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
header `x-goog-api-key: {key}`; body `contents[].role/parts[].text`,
`systemInstruction`, `generationConfig.temperature`.

### System prompt

Ported from `intruksi.md` and **hardened**: the assistant answers only about
this user's personal finances, notes and tasks, and politely declines anything
outside that scope.

## 10. Polling, not WebSocket

SLand has no OkHttp/Ktor — only `HttpURLConnection` (`Net.kt`). Supabase
Realtime needs a WebSocket, and a persistent WS in an AccessibilityService is a
real battery cost for data where a 30s delay is imperceptible.

Follow `Github.kt`: 30s while a wallet panel is open, 5–10min when closed,
**fully stopped** when the screen is off or the network is down.

## 11. Keys — all runtime, no new CI secrets

Supabase URL, anon key, Gemini key and the sync token are entered by the user
in-app and stored in `Vault` (Keystore-backed), following the GitHub-token
precedent rather than the build-time `CARTO_KEY` precedent.

**The GitHub Actions workflow is unchanged. No new repository secrets.**

Settings gets a wizard with a real **Test Connection** button that queries
`wallets` and reports precisely what failed: bad URL / bad key / missing tables
/ missing policies. Config without a test button generates needless confusion.

## 12. Tab renumbering

Orders 1–11 are fully taken. New layout:

```
1 music   2 finance*  3 timer   4 agenda  5 prayer  6 agents
7 notes   8 shelf     9 kanban* 10 ai*    11 github 12 shots
13 maps   14 apps
```

Each new tab touches six sites: a `TabScene` subclass, `register()` in
`Listener.kt`, its `order`, an `Icon` entry + path in `Icons.kt`, a `Triple` in
`Section.kt:607`, and strings in both `values/` and `values-in/`.
