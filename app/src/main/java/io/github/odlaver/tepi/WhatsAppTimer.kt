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

    // Different messages posted with the same notification id inside one
    // Android update collapse into a single callback, so the id carries the
    // message count forward — that is what lets one sound fire per message.
    private const val NOTIFY_ID_BASE = 0x5741000
    private const val OLD_CHANNEL_ID = "whatsapp_target_alert"
    private const val CHANNEL_ID = "whatsapp_target_alert_silent"

    private var player: MediaPlayer? = null
    private var playingId = 0
    private var playing = false
    private var queued = 0
    private var appContext: Context? = null
    private var soundUri: String? = null

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
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            ensureChannel(context, manager)
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false

            val id = NOTIFY_ID_BASE or (target.number.hashCode() and 0xffff)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(
                context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(context.getString(R.string.wa_timer_notification_title, target.name))
                .setContentText(context.getString(R.string.wa_timer_notification_body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            manager.notify(id, builder.build())
            // Sounds are queued, not restarted: three messages landing together
            // are three sounds in a row rather than the last one cutting off the
            // rest. The silent channel keeps Android's own sound out of the way,
            // so exactly one sound is heard per message.
            sound(context, prefs)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(OLD_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.wa_timer_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    // The queue itself. Every matching message adds one sound. The first plays
    // at once, because the contact is reaching out now; if a sound is already
    // running, the new one waits its turn instead of cutting it off, and plays
    // the moment the one before it ends. Three messages in a minute are three
    // alerts in a row — one sound per message, and the last always plays.
    @Synchronized
    private fun sound(context: Context, prefs: Prefs) {
        appContext = context.applicationContext
        soundUri = prefs.str(SOUND_URI, "").takeIf { it.isNotBlank() }
        if (playing) {
            queued++
            return
        }
        playing = true
        startSound()
    }

    private fun startSound() {
        val context = appContext
        if (context == null) {
            playing = false
            player = null
            queued = 0
            return
        }
        val id = ++playingId
        val next = MediaPlayer()
        try { player?.release() } catch (_: Exception) { }
        player = next
        next.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        try {
            val uri = soundUri
            if (uri != null) next.setDataSource(context, Uri.parse(uri))
            else next.setDataSource(defaultSound(context).absolutePath)
            next.setOnPreparedListener { it.start() }
            next.setOnCompletionListener { mp ->
                try { mp.release() } catch (_: Exception) { }
                if (playingId == id) nextSound()
            }
            next.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Exception) { }
                if (playingId == id) nextSound()
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            try { next.release() } catch (_: Exception) { }
            if (playingId == id) nextSound()
        }
    }

    @Synchronized
    private fun nextSound() {
        if (queued > 0) {
            queued--
            startSound()
        } else {
            playing = false
            player = null
            appContext = null
        }
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
