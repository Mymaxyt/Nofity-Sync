package com.notifysync.core

/** Kompaktes Textformat für Lese-Meldungen. Wird vor dem Versand verschlüsselt. */
object EventCodec {
    private const val VERSION = "v1"

    fun encode(e: ReadEvent): ByteArray = listOf(
        VERSION,
        e.eventId,
        e.fromDevice,
        e.packageName,
        e.kind.name,
        e.conversationHash.orEmpty(),
        e.messageHashes.joinToString(","),
        e.contentHash.orEmpty(),
        e.readAt.toString(),
        e.reason.name,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ReadEvent {
        val p = bytes.toString(Charsets.UTF_8).split("\n")
        require(p.size == 10 && p[0] == VERSION) { "Unbekanntes Format" }
        return ReadEvent(
            eventId = p[1],
            fromDevice = p[2],
            packageName = p[3],
            kind = EventKind.valueOf(p[4]),
            conversationHash = p[5].ifEmpty { null },
            messageHashes = if (p[6].isEmpty()) emptySet() else p[6].split(",").toSet(),
            contentHash = p[7].ifEmpty { null },
            readAt = p[8].toLong(),
            reason = ReadReason.valueOf(p[9]),
        )
    }
}
