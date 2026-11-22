package com.notifysync.core

/** Gründe aus NotificationListenerService.onNotificationRemoved (Werte aus dem Android SDK). */
object RemovalReasons {
    const val CLICK = 1
    const val CANCEL = 2
    const val CANCEL_ALL = 3
    const val APP_CANCEL = 8
    const val APP_CANCEL_ALL = 9
    const val LISTENER_CANCEL = 10
}

sealed interface Decision {
    /** Darf entfernt werden. [matchedEvents] belegt, welche Lese-Meldungen das rechtfertigen. */
    data class Remove(val match: String, val matchedEvents: List<String>) : Decision
    /** Es gibt eine passende Lese-Meldung, aber eine Regel verbietet das Entfernen. */
    data class Keep(val reason: String) : Decision
    /** Keine passende Lese-Meldung, nichts zu tun. */
    data object NoMatch : Decision
}

/**
 * Die Sicherheitsregel aus dem Konzept, als reine Logik:
 *  1. Nur echte Lese-Signale (geöffnet oder in der App gelesen), Wegwischen zählt nicht.
 *  2. Nichts Neueres: jede Nachricht der Tablet-Benachrichtigung muss am Handy gelesen worden sein
 *     und darf nicht nach dem Lesezeitpunkt eingegangen sein.
 *  3. Sicherer Treffer: Chat-ID und Nachrichteninhalt müssen passen.
 *  Papierkorb und Probelauf setzt die Android-Schicht um.
 */
class SyncRules(
    private val fp: Fingerprinter,
    private val localDeviceId: String,
    private val eventLifetimeMs: Long = 24 * 60 * 60 * 1000L,
) {

    fun isReadSignal(reason: Int): Boolean =
        reason == RemovalReasons.CLICK || reason == RemovalReasons.APP_CANCEL

    /** Gründe, die nie zu einer Meldung führen dürfen, fürs Protokoll. */
    fun describeReason(reason: Int): String = when (reason) {
        RemovalReasons.CLICK -> "geöffnet"
        RemovalReasons.CANCEL -> "weggewischt"
        RemovalReasons.CANCEL_ALL -> "alle gelöscht"
        RemovalReasons.APP_CANCEL -> "von der App entfernt (gelesen)"
        RemovalReasons.APP_CANCEL_ALL -> "App hat alle entfernt"
        RemovalReasons.LISTENER_CANCEL -> "von Notify-Sync entfernt"
        else -> "Systemgrund $reason"
    }

    fun isEligible(s: NotificationSnapshot): Boolean =
        !s.isLive && !s.isRedacted && !s.isGroupSummary

    /**
     * Quellgerät: aus einer entfernten Benachrichtigung eine Lese-Meldung bauen.
     * [stillActive] sind die aktiven Benachrichtigungen ein paar Sekunden später. Nachrichten, die
     * dort wieder auftauchen (App hat neu gepostet statt gelesen), werden nicht gemeldet.
     */
    fun buildReadEvent(
        removed: NotificationSnapshot,
        reason: Int,
        readAt: Long,
        stillActive: List<NotificationSnapshot>,
        eventId: String,
    ): ReadEvent? {
        if (!isReadSignal(reason) || !isEligible(removed)) return null
        val readReason = if (reason == RemovalReasons.CLICK) ReadReason.OPENED else ReadReason.READ_IN_APP

        if (removed.isMessaging) {
            val conv = fp.conversationHash(removed) ?: return null
            val stillShown = stillActive
                .filter { it.packageName == removed.packageName && fp.conversationHash(it) == conv }
                .flatMap { s -> s.messages.map { fp.messageHash(s, it) } }
                .toSet()
            val hashes = removed.messages
                .filter { it.timestamp <= readAt }
                .map { fp.messageHash(removed, it) }
                .filterNot { it in stillShown }
                .toSet()
            if (hashes.isEmpty()) return null
            return ReadEvent(
                eventId, localDeviceId, removed.packageName, EventKind.CONVERSATION,
                conv, hashes, null, readAt, readReason,
            )
        }

        val content = fp.contentHash(removed)
        if (stillActive.any { !it.isMessaging && it.packageName == removed.packageName && fp.contentHash(it) == content }) {
            return null
        }
        return ReadEvent(
            eventId, localDeviceId, removed.packageName, EventKind.CONTENT,
            null, emptySet(), content, readAt, readReason,
        )
    }

    /** Zielgerät: darf diese Benachrichtigung aufgrund der Lese-Meldungen entfernt werden? */
    fun decide(target: NotificationSnapshot, events: Collection<ReadEvent>, now: Long): Decision {
        val relevant = events.filter {
            it.fromDevice != localDeviceId &&
                it.packageName == target.packageName &&
                now - it.readAt <= eventLifetimeMs
        }
        if (relevant.isEmpty()) return Decision.NoMatch
        if (target.isLive) return Decision.NoMatch
        if (target.isGroupSummary) return Decision.NoMatch

        if (target.isMessaging) {
            val conv = fp.conversationHash(target)
            val convEvents = relevant.filter { it.kind == EventKind.CONVERSATION && it.conversationHash != null }
            if (conv == null) {
                return if (convEvents.isEmpty()) Decision.NoMatch else Decision.Keep("Keine Chat-ID, kein sicherer Treffer")
            }
            val matching = convEvents.filter { it.conversationHash == conv }
            if (matching.isEmpty()) return Decision.NoMatch
            if (target.isRedacted) return Decision.Keep("Inhalt von Android geschwärzt")

            for (m in target.messages) {
                val h = fp.messageHash(target, m)
                val readIn = matching.filter { h in it.messageHashes }
                if (readIn.isEmpty()) return Decision.Keep("Enthält eine Nachricht, die am anderen Gerät nicht gelesen wurde")
                if (m.timestamp > readIn.maxOf { it.readAt }) {
                    return Decision.Keep("Enthält eine Nachricht, die nach dem Lesen eingegangen ist")
                }
            }
            return Decision.Remove("Chat-ID + Nachrichten", matching.map { it.eventId })
        }

        if (target.isRedacted) return Decision.NoMatch
        val content = fp.contentHash(target)
        val matching = relevant.filter { it.kind == EventKind.CONTENT && it.contentHash == content }
        return if (matching.isEmpty()) Decision.NoMatch else Decision.Remove("Exakter Inhalt", matching.map { it.eventId })
    }

    /**
     * Sammel-Benachrichtigungen (Gruppen-Summary) einer Gruppe, in der nach dem Entfernen keine
     * Einzel-Benachrichtigung mehr übrig ist. Sie enthalten selbst keine ungelesene Nachricht.
     */
    fun orphanedSummaries(active: List<NotificationSnapshot>, touchedGroups: Set<Pair<String, String>>): List<NotificationSnapshot> =
        active.filter { s ->
            s.isGroupSummary && s.groupKey != null && (s.packageName to s.groupKey) in touchedGroups &&
                active.none { !it.isGroupSummary && it.packageName == s.packageName && it.groupKey == s.groupKey }
        }
}
