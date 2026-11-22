package com.notifysync.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRulesTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val crypto = PairingCrypto(secret)
    private val fp = Fingerprinter(crypto)
    private val phone = SyncRules(fp, "phone")
    private val tablet = SyncRules(fp, "tablet")

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun wa(
        key: String,
        chat: String? = "4917000000@s.whatsapp.net",
        vararg msgs: Pair<String, Long>,
        live: Boolean = false,
        redacted: Boolean = false,
    ) = NotificationSnapshot(
        key = key, packageName = "com.whatsapp", channelId = "individual_chat_defaults",
        conversationId = chat, conversationTitle = null, title = "Anna", text = msgs.lastOrNull()?.first,
        subText = null, bigText = null,
        messages = msgs.map { ChatMessage("Anna", it.first, it.second) },
        isGroupSummary = false, groupKey = "group_key_messages", postTime = msgs.maxOfOrNull { it.second } ?: t0,
        isLive = live, isRedacted = redacted,
    )

    private fun mail(key: String, subject: String) = NotificationSnapshot(
        key, "com.google.android.gm", "mail", null, null, "Bob", subject, "ich@example.org", null,
        emptyList(), false, null, t0, isLive = false, isRedacted = false,
    )

    @Test fun `geöffnet am Handy entfernt dieselbe WhatsApp-Benachrichtigung am Tablet`() {
        val onPhone = wa("p1", msgs = arrayOf("Hallo" to t0, "Kommst du?" to t0 + min))
        val e = phone.buildReadEvent(onPhone, RemovalReasons.CLICK, t0 + 2 * min, emptyList(), "e1")
        assertNotNull(e)
        val onTablet = wa("t9", msgs = arrayOf("Hallo" to t0, "Kommst du?" to t0 + min))
        assertIs<Decision.Remove>(tablet.decide(onTablet, listOf(e), t0 + 3 * min))
    }

    @Test fun `im WhatsApp-Chat gelesen zählt als Lese-Signal`() {
        val onPhone = wa("p1", msgs = arrayOf("Hallo" to t0))
        assertNotNull(phone.buildReadEvent(onPhone, RemovalReasons.APP_CANCEL, t0 + min, emptyList(), "e1"))
    }

    @Test fun `Wegwischen und alle löschen erzeugen nie eine Meldung`() {
        val onPhone = wa("p1", msgs = arrayOf("Hallo" to t0))
        for (r in listOf(RemovalReasons.CANCEL, RemovalReasons.CANCEL_ALL, RemovalReasons.APP_CANCEL_ALL, RemovalReasons.LISTENER_CANCEL, 18)) {
            assertNull(phone.buildReadEvent(onPhone, r, t0 + min, emptyList(), "e$r"), "Grund $r")
        }
    }

    @Test fun `neuere Nachricht am Tablet bleibt stehen`() {
        val onPhone = wa("p1", msgs = arrayOf("Hallo" to t0))
        val e = phone.buildReadEvent(onPhone, RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        val onTablet = wa("t1", msgs = arrayOf("Hallo" to t0, "Noch was!" to t0 + 2 * min))
        assertIs<Decision.Keep>(tablet.decide(onTablet, listOf(e), t0 + 3 * min))
    }

    @Test fun `gleicher Text später geschickt bleibt stehen`() {
        val onPhone = wa("p1", msgs = arrayOf("ok" to t0))
        val e = phone.buildReadEvent(onPhone, RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        val onTablet = wa("t1", msgs = arrayOf("ok" to t0 + 5 * min))
        assertIs<Decision.Keep>(tablet.decide(onTablet, listOf(e), t0 + 6 * min))
    }

    @Test fun `anderer Chat wird nicht angefasst`() {
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        val other = wa("t1", chat = "4917111111@s.whatsapp.net", msgs = arrayOf("Hallo" to t0))
        assertEquals(Decision.NoMatch, tablet.decide(other, listOf(e), t0 + 2 * min))
    }

    @Test fun `ohne Chat-ID kein Abgleich`() {
        assertNull(phone.buildReadEvent(wa("p1", chat = null, msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1"))
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertIs<Decision.Keep>(tablet.decide(wa("t1", chat = null, msgs = arrayOf("Hallo" to t0)), listOf(e), t0 + 2 * min))
    }

    @Test fun `ältere am Handy früher gelesene Nachrichten zählen zusammen`() {
        val e1 = phone.buildReadEvent(wa("p1", msgs = arrayOf("A" to t0)), RemovalReasons.APP_CANCEL, t0 + min, emptyList(), "e1")!!
        val e2 = phone.buildReadEvent(wa("p2", msgs = arrayOf("B" to t0 + 2 * min)), RemovalReasons.APP_CANCEL, t0 + 3 * min, emptyList(), "e2")!!
        val onTablet = wa("t1", msgs = arrayOf("A" to t0, "B" to t0 + 2 * min))
        assertIs<Decision.Remove>(tablet.decide(onTablet, listOf(e1, e2), t0 + 4 * min))
        assertIs<Decision.Keep>(tablet.decide(onTablet, listOf(e1), t0 + 4 * min))
    }

    @Test fun `App postet neu statt zu lesen - keine Meldung`() {
        val removed = wa("p1", msgs = arrayOf("Hallo" to t0))
        val reposted = wa("p2", msgs = arrayOf("Hallo" to t0, "Neu" to t0 + min))
        assertNull(phone.buildReadEvent(removed, RemovalReasons.APP_CANCEL, t0 + min, listOf(reposted), "e1"))
    }

    @Test fun `Live-Benachrichtigungen werden nie gemeldet oder entfernt`() {
        assertNull(phone.buildReadEvent(wa("p1", msgs = arrayOf("Anruf" to t0), live = true), RemovalReasons.CLICK, t0 + min, emptyList(), "e1"))
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Anruf" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertEquals(Decision.NoMatch, tablet.decide(wa("t1", msgs = arrayOf("Anruf" to t0), live = true), listOf(e), t0 + 2 * min))
    }

    @Test fun `geschwärzte Inhalte werden nicht entfernt`() {
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertIs<Decision.Keep>(tablet.decide(wa("t1", msgs = arrayOf("Hallo" to t0), redacted = true), listOf(e), t0 + 2 * min))
    }

    @Test fun `Meldungen älter als 24 Stunden zählen nicht`() {
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertEquals(Decision.NoMatch, tablet.decide(wa("t1", msgs = arrayOf("Hallo" to t0)), listOf(e), t0 + 25 * 60 * min))
    }

    @Test fun `eigene Meldungen werden ignoriert`() {
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertEquals(Decision.NoMatch, phone.decide(wa("p2", msgs = arrayOf("Hallo" to t0)), listOf(e), t0 + 2 * min))
    }

    @Test fun `Mail mit gleichem Inhalt wird entfernt, andere nicht`() {
        val e = phone.buildReadEvent(mail("p1", "Rechnung"), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        assertIs<Decision.Remove>(tablet.decide(mail("t1", "Rechnung"), listOf(e), t0 + 2 * min))
        assertEquals(Decision.NoMatch, tablet.decide(mail("t2", "Mahnung"), listOf(e), t0 + 2 * min))
    }

    @Test fun `verwaiste Sammelbenachrichtigung wird erkannt`() {
        val summary = wa("s", msgs = arrayOf()).copy(isGroupSummary = true)
        val child = wa("c", msgs = arrayOf("x" to t0))
        val g = setOf("com.whatsapp" to "group_key_messages")
        assertEquals(listOf(summary), tablet.orphanedSummaries(listOf(summary), g))
        assertTrue(tablet.orphanedSummaries(listOf(summary, child), g).isEmpty())
        assertTrue(tablet.orphanedSummaries(listOf(summary), emptySet()).isEmpty())
    }

    @Test fun `Verschlüsselung und Format überstehen den Rundweg`() {
        val e = phone.buildReadEvent(wa("p1", msgs = arrayOf("Hallo" to t0, "Du?" to t0)), RemovalReasons.CLICK, t0 + min, emptyList(), "e1")!!
        val box = crypto.encrypt(EventCodec.encode(e))
        assertEquals(e, EventCodec.decode(PairingCrypto(secret).decrypt(box)))
        assertTrue(!String(box, Charsets.ISO_8859_1).contains("Hallo"))
    }

    @Test fun `manipulierte oder fremde Pakete werden abgelehnt`() {
        val box = crypto.encrypt("x".toByteArray())
        box[box.size - 1] = (box[box.size - 1] + 1).toByte()
        assertFailsWith<Exception> { crypto.decrypt(box) }
        val foreign = PairingCrypto(ByteArray(32) { 7 }).encrypt("x".toByteArray())
        assertFailsWith<Exception> { crypto.decrypt(foreign) }
    }

    @Test fun `Prüfcode ist auf beiden Geräten gleich`() {
        assertEquals(PairingCrypto(secret).verificationCode(), crypto.verificationCode())
        assertEquals(6, crypto.verificationCode().length)
    }
}
