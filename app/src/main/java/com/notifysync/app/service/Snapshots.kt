package com.notifysync.app.service

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.notifysync.core.ChatMessage
import com.notifysync.core.NotificationSnapshot

/** Übersetzt eine Android-Benachrichtigung in die plattformunabhängige Momentaufnahme. */
object Snapshots {

    fun from(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val ex = n.extras
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = style?.messages.orEmpty().map {
            ChatMessage(
                sender = it.person?.name?.toString().orEmpty(),
                text = it.text?.toString().orEmpty(),
                timestamp = it.timestamp,
            )
        }
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            channelId = n.channelId,
            conversationId = n.shortcutId,
            conversationTitle = style?.conversationTitle?.toString(),
            title = title,
            text = text,
            subText = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            messages = messages,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            groupKey = n.group,
            postTime = sbn.postTime,
            isLive = isLive(n),
            isRedacted = isRedacted(text) || messages.any { isRedacted(it.text) },
        )
    }

    /**
     * Live-Benachrichtigungen sind vom Abgleich immer ausgenommen: laufende Anrufe, Medien,
     * Navigation, Fortschritt, Timer/Stoppuhr, Wecker, Vordergrunddienste, Live-Updates.
     */
    fun isLive(n: Notification): Boolean {
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (n.category in LIVE_CATEGORIES) return true
        val ex = n.extras
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (LIVE_TEMPLATES.any { template.contains(it) }) return true
        if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return true
        if (ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return true
        if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) return true
        return false
    }

    /** Android 15+ schwärzt Einmalcodes für Listener-Apps. Solche Benachrichtigungen fassen wir nicht an. */
    private fun isRedacted(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val l = s.lowercase()
        return REDACTION_MARKERS.any { l.contains(it) }
    }

    private val LIVE_CATEGORIES = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_STOPWATCH,
        Notification.CATEGORY_ALARM,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_LOCATION_SHARING,
        Notification.CATEGORY_WORKOUT,
    )

    private val LIVE_TEMPLATES = listOf("MediaStyle", "CallStyle", "DecoratedMediaCustomViewStyle", "ProgressStyle")

    private val REDACTION_MARKERS = listOf(
        "sensitive notification content hidden",
        "vertraulicher inhalt",
        "inhalt ausgeblendet",
    )
}
