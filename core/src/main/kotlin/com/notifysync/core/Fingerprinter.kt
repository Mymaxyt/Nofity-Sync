package com.notifysync.core

/**
 * Bildet geräteunabhängige, gesalzene Fingerabdrücke. Die Android-Schlüssel einer
 * Benachrichtigung unterscheiden sich zwischen Handy und Tablet, deshalb wird über
 * Paket, Chat-ID und Inhalt verglichen.
 */
class Fingerprinter(private val crypto: PairingCrypto) {

    /** Null, wenn die App keine Chat-ID liefert: dann ist kein sicherer Treffer möglich. */
    fun conversationHash(s: NotificationSnapshot): String? =
        s.conversationId?.takeIf { it.isNotBlank() }?.let { crypto.hash("conv", s.packageName, it) }

    fun messageHash(s: NotificationSnapshot, m: ChatMessage): String =
        crypto.hash("msg", s.packageName, s.conversationId, normalize(m.text))

    fun contentHash(s: NotificationSnapshot): String =
        crypto.hash(
            "content", s.packageName, s.channelId,
            normalize(s.title), normalize(s.text), normalize(s.subText), normalize(s.bigText),
        )

    private fun normalize(v: String?): String? = v?.trim()?.replace(Regex("\\s+"), " ")
}
