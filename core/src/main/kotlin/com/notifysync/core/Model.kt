package com.notifysync.core

/** Eine einzelne Chat-Nachricht aus einer MessagingStyle-Benachrichtigung (z. B. WhatsApp). */
data class ChatMessage(
    val sender: String,
    val text: String,
    /** Zeitpunkt der Nachricht laut App, in Millisekunden. */
    val timestamp: Long,
)

/**
 * Plattformunabhängige Momentaufnahme einer Benachrichtigung. Die Android-Schicht füllt sie aus
 * einer StatusBarNotification; alle Entscheidungen laufen auf dieser Klasse, damit sie ohne
 * Gerät testbar sind.
 */
data class NotificationSnapshot(
    /** Android-Schlüssel, nur auf diesem Gerät gültig. */
    val key: String,
    val packageName: String,
    val channelId: String?,
    /** Shortcut-/Konversations-ID (bei WhatsApp die Chat-ID), falls die App sie liefert. */
    val conversationId: String?,
    val conversationTitle: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val messages: List<ChatMessage>,
    val isGroupSummary: Boolean,
    val groupKey: String?,
    val postTime: Long,
    /** Anruf, Medien, Navigation, Fortschritt, Timer, Vordergrunddienst … */
    val isLive: Boolean,
    /** Android hat Inhalte geschwärzt (z. B. Einmalcodes ab Android 15). */
    val isRedacted: Boolean,
) {
    val isMessaging: Boolean get() = messages.isNotEmpty()
}

enum class ReadReason { OPENED, READ_IN_APP }

enum class EventKind {
    /** Chat-Benachrichtigung: Abgleich über Chat-ID und einzelne Nachrichten. */
    CONVERSATION,
    /** Sonstige Benachrichtigung: Abgleich über exakten Inhalt. */
    CONTENT,
}

/**
 * "Auf Gerät X gelesen". Enthält nur gesalzene Hashes, nie Inhalte.
 */
data class ReadEvent(
    val eventId: String,
    val fromDevice: String,
    val packageName: String,
    val kind: EventKind,
    val conversationHash: String?,
    val messageHashes: Set<String>,
    val contentHash: String?,
    val readAt: Long,
    val reason: ReadReason,
)
