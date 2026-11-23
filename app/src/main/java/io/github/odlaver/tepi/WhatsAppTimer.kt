package io.github.odlaver.tepi

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppTimerTarget(val name: String, val number: String)

object WhatsAppTimer {
    const val ENABLED = "wa.timer.enabled"
    const val CONTACTS = "wa.timer.contacts"
    const val COOLDOWN_MS = 5 * 60_000L

    fun load(prefs: Prefs): List<WhatsAppTimerTarget> {
        val raw = prefs.str(CONTACTS, "")
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<WhatsAppTimerTarget>()
        return try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val number = normalize(o.optString("number"))
                if (name.isNotEmpty() && number.isNotEmpty()) out.add(WhatsAppTimerTarget(name, number))
                if (out.size >= 5) break
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    fun save(prefs: Prefs, targets: List<WhatsAppTimerTarget>) {
        val a = JSONArray()
        targets.take(5).forEach { target ->
            a.put(JSONObject().put("name", target.name).put("number", normalize(target.number)))
        }
        prefs.set(CONTACTS, a.toString())
    }

    fun normalize(value: String): String {
        var digits = value.filter(Char::isDigit)
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.startsWith("0")) digits = "62" + digits.drop(1)
        return digits
    }

    fun matches(prefs: Prefs, title: String, notification: Notification): WhatsAppTimerTarget? {
        val titleText = title.trim()
        val extras = notification.extras
        val extrasText = buildString {
            for (key in listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE)) {
                extras.getCharSequence(key)?.let { append(' ').append(it) }
            }
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { value ->
                val bundle = value as? android.os.Bundle ?: return@forEach
                bundle.getCharSequence("text")?.let { append(' ').append(it) }
                bundle.getCharSequence("sender")?.let { append(' ').append(it) }
            }
        }
        val textToInspect = "$titleText $extrasText"

        val names = textToInspect.split(Regex("[\\n|:•]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (target in load(prefs)) {
            val nameMatch = target.name.isNotEmpty() && names.any { it.equals(target.name, ignoreCase = true) }
            val explicitNumber = Regex("\\d{8,15}").findAll(textToInspect).map { normalize(it.value) }.any { it == target.number }
            if (nameMatch || explicitNumber) return target
        }
        return null
    }

    fun startSystemTimer(context: Context, target: WhatsAppTimerTarget, prefs: Prefs): Boolean {
        val now = System.currentTimeMillis()
        val key = "wa.timer.cooldown." + target.number
        val last = prefs.long(key, 0)
        if (last > 0 && now - last < COOLDOWN_MS) return false
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, 10)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Timer WhatsApp: ${target.name}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved == null) false else {
                context.startActivity(intent)
                prefs.set(key, now)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
