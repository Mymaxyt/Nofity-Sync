package com.notifysync.app.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notifysync.app.SyncEngine

/**
 * Wird vom System gebunden, sobald Max den Benachrichtigungszugriff erteilt, und läuft dann
 * dauerhaft. Alle Entscheidungen trifft die [SyncEngine].
 */
class SyncListenerService : NotificationListenerService() {

    private val engine get() = SyncEngine.get(this)

    override fun onListenerConnected() {
        engine.onServiceConnected(this)
    }

    override fun onListenerDisconnected() {
        engine.onServiceDisconnected(this)
        // Samsung trennt Listener gelegentlich; sofort neu binden lassen.
        requestRebind(ComponentName(this, SyncListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        engine.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        engine.onRemoved(sbn, reason)
    }

    fun active(): List<StatusBarNotification> =
        runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())

    fun cancel(key: String) {
        runCatching { cancelNotification(key) }
    }
}
