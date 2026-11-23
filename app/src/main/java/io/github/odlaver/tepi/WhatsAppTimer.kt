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
        val nameCandidates = ArrayList<String>()
        if (titleText.isNotEmpty()) nameCandidates.add(titleText)
        for (key in listOf(Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE)) {
            extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { nameCandidates.add(it) }
        }

        val textToInspect = buildString {
            append(titleText)
            for (key in listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_CONVERSATION_TITLE, Notification.EXTRA_TITLE)) {
                extras.getCharSequence(key)?.let { append(' ').append(it) }
            }
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { value ->
                val bundle = value as? android.os.Bundle ?: return@forEach
                bundle.getCharSequence("text")?.let { append(' ').append(it) }
                bundle.getCharSequence("sender")?.let { sender ->
                    nameCandidates.add(sender.toString())
                    append(' ').append(sender)
                }
                val personName = bundle.getParcelable<android.app.Person>("sender_person")?.name?.toString()?.trim()
                personName?.takeIf { it.isNotEmpty() }?.let { nameCandidates.add(it); append(' ').append(it) }
            }
        }.toString()

        for (target in load(prefs)) {
            val nameMatch = target.name.isNotEmpty() && nameCandidates.any { candidate ->
                val comparable = candidate
                    .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                    .replace(Regex("\\s*[·•|-]\\s*\\d+(\\s*(new|pesan|baru|messages?))?\\s*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                comparable.equals(target.name, ignoreCase = true) ||
                    Regex("(?i)(^|[^A-Za-z0-9])${Regex.escape(target.name)}([^A-Za-z0-9]|$)").containsMatchIn(comparable)
            }
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
            context.startActivity(intent)
            prefs.set(key, now)
            true
        } catch (_: Exception) {
            false
        }
    }
}
