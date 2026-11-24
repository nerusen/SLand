package io.github.odlaver.tepi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppTimerTarget(val name: String, val number: String)

object WhatsAppTimer {
    const val ENABLED = "wa.timer.enabled"
    const val CONTACTS = "wa.timer.contacts"
    const val SOUND_URI = "wa.timer.sound.uri"
    const val COOLDOWN_MS = 5 * 60_000L
    private const val CHANNEL_ID = "whatsapp_target_alert"
    private var player: MediaPlayer? = null

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
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val identities = buildList {
            if (!group) {
                add(titleText)
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(::add)
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let(::add)
            }
            val last = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle
            last?.getCharSequence("sender")?.toString()?.let(::add)
            val person = last?.getParcelable<android.app.Person>("sender_person")
            person?.name?.toString()?.let(::add)
            person?.uri?.let(::add)
        }
        val textToInspect = identities.joinToString(" ")
        val names = identities.flatMap { it.split(Regex("[\\n|:•]+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (target in load(prefs)) {
            val nameMatch = target.name.isNotEmpty() && names.any {
                it.equals(target.name, ignoreCase = true) || it.startsWith("${target.name}:", ignoreCase = true)
            }
            val explicitNumber = Regex("\\d{8,15}").findAll(textToInspect).map { normalize(it.value) }.any { it == target.number }
            if (nameMatch || explicitNumber) return target
        }
        return null
    }

    fun notifyTarget(context: Context, target: WhatsAppTimerTarget, prefs: Prefs): Boolean {
        val now = System.currentTimeMillis()
        val key = "wa.timer.cooldown." + target.number
        val last = prefs.long(key, 0)
        if (last > 0 && now - last < COOLDOWN_MS) return false
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            ensureChannel(context, manager)
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val open = PendingIntent.getActivity(
                context, target.number.hashCode(), Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(context.getString(R.string.wa_timer_notification_title, target.name))
                .setContentText(context.getString(R.string.wa_timer_notification_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
            manager.notify(target.number.hashCode(), notification)
            prefs.set(key, now)
            playSound(context, prefs)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.wa_timer_channel), NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    @Synchronized
    private fun playSound(context: Context, prefs: Prefs) {
        startSound(context, prefs.str(SOUND_URI, "").takeIf { it.isNotBlank() })
    }

    @Synchronized
    private fun startSound(context: Context, customUri: String?) {
        try { player?.release() } catch (_: Exception) { }
        player = null
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        try {
            if (customUri != null) next.setDataSource(context, Uri.parse(customUri))
            else next.setDataSource(defaultSound(context).absolutePath)
            next.setOnPreparedListener { it.start() }
            next.setOnCompletionListener { releasePlayer(it) }
            next.setOnErrorListener { mp, _, _ ->
                releasePlayer(mp)
                if (customUri != null) startSound(context, null)
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            releasePlayer(next)
            if (customUri != null) startSound(context, null)
        }
    }

    @Synchronized
    private fun releasePlayer(mp: MediaPlayer) {
        if (player === mp) player = null
        try { mp.release() } catch (_: Exception) { }
    }

    private fun defaultSound(context: Context): File {
        val file = File(context.filesDir, "whatsapp-alert.ogg")
        if (!file.exists() || file.length() == 0L) {
            context.assets.open("sland-notification.ogg").use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}
