package com.notifysync.app

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.notifysync.app.relay.FirebaseRelay
import com.notifysync.app.service.Snapshots
import com.notifysync.app.service.SyncListenerService
import com.notifysync.app.storage.DAY
import com.notifysync.app.storage.EventStore
import com.notifysync.app.storage.Journal
import com.notifysync.app.storage.LogType
import com.notifysync.app.storage.Pairing
import com.notifysync.app.storage.Trash
import com.notifysync.app.storage.TrashEntry
import com.notifysync.app.storage.Vault
import com.notifysync.core.Decision
import com.notifysync.core.Fingerprinter
import com.notifysync.core.NotificationSnapshot
import com.notifysync.core.PairingCrypto
import com.notifysync.core.ReadEvent
import com.notifysync.core.RemovalReasons
import com.notifysync.core.SyncRules
import com.notifysync.core.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Herz der App: verbindet Benachrichtigungsdienst, Sicherheitsregeln, Relay und lokale Ablage.
 * Alle Aufrufe laufen auf dem Hauptthread.
 */
class SyncEngine private constructor(private val ctx: Context) : FirebaseRelay.Listener {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val vault = Vault(ctx)
    val journal = Journal(vault)
    val trash = Trash(vault)
    private val events = EventStore(vault)
    private val main = Handler(Looper.getMainLooper())

    private val _changes = MutableStateFlow(0)
    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu zeichnet. */
    val changes: StateFlow<Int> = _changes

    var relayStatus: String = "Nicht gekoppelt"
        private set

    private var service: SyncListenerService? = null
    private var relay: FirebaseRelay? = null
    private var crypto: PairingCrypto? = null
    private var rules: SyncRules? = null
    private val contentIntents = HashMap<String, PendingIntent>()
    private val loggedDecisions = HashSet<String>()

    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().take(8).also {
        prefs.edit().putString("deviceId", it).apply()
    }

    var pairing: Pairing? = vault.read(PAIRING)?.let { runCatching { Pairing.fromJson(it) }.getOrNull() }
        private set

    init {
        applyPairing()
    }

    val isServiceConnected: Boolean get() = service != null

    /** Probelauf: nur protokollieren, nichts entfernen. Standard an. */
    var dryRun: Boolean
        get() = prefs.getBoolean("dryRun", true)
        set(v) {
            prefs.edit().putBoolean("dryRun", v).apply()
            journal.add(LogType.INFO, "", if (v) "Probelauf eingeschaltet" else "Probelauf ausgeschaltet", if (v) "Es wird nur protokolliert." else "Ab jetzt wird wirklich entfernt.")
            if (!v) evaluateAll()
            changed()
        }

    fun excluded(): Set<String> = prefs.getStringSet("excluded", emptySet()).orEmpty()
    fun seenPackages(): Set<String> = prefs.getStringSet("seen", emptySet()).orEmpty()

    fun setExcluded(pkg: String, excluded: Boolean) {
        val s = excluded().toMutableSet()
        if (excluded) s += pkg else s -= pkg
        prefs.edit().putStringSet("excluded", s).apply()
        changed()
    }

    fun appLabel(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)

    // ---------------------------------------------------------------- Kopplung

    fun createPairing(projectId: String, appId: String, apiKey: String) {
        val p = Pairing(projectId.trim(), appId.trim(), apiKey.trim(), PairingCrypto.newSecret().toHex())
        savePairing(p)
    }

    /** @throws IllegalArgumentException bei ungültigem Code */
    fun joinPairing(code: String) {
        val trimmed = code.trim()
        require(trimmed.startsWith(CODE_PREFIX)) { "Das ist kein Notify-Sync-Kopplungscode." }
        val json = String(Base64.decode(trimmed.removePrefix(CODE_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        savePairing(Pairing.fromJson(json))
    }

    fun pairingCode(): String? = pairing?.let {
        CODE_PREFIX + Base64.encodeToString(it.toJson().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun verificationCode(): String? = crypto?.verificationCode()

    fun unpair() {
        relay?.stop()
        relay = null
        vault.delete(PAIRING)
        events.clear()
        pairing = null
        crypto = null
        rules = null
        relayStatus = "Nicht gekoppelt"
        journal.add(LogType.INFO, "", "Kopplung aufgehoben", "Schlüssel gelöscht")
        changed()
    }

    private fun savePairing(p: Pairing) {
        vault.write(PAIRING, p.toJson())
        prefs.edit().putLong("since", System.currentTimeMillis() - DAY).apply()
        pairing = p
        journal.add(LogType.INFO, "", "Gekoppelt", "Firebase-Projekt ${p.projectId}")
        applyPairing()
    }

    private fun applyPairing() {
        relay?.stop()
        relay = null
        val p = pairing ?: return
        val c = PairingCrypto(hexToBytes(p.secretHex))
        crypto = c
        rules = SyncRules(Fingerprinter(c), deviceId)
        try {
            relay = FirebaseRelay(ctx, p, c, deviceId, this).also {
                it.start(prefs.getLong("since", System.currentTimeMillis() - DAY))
            }
        } catch (e: Exception) {
            relayStatus = "Firebase-Fehler: ${e.message}"
            journal.add(LogType.ERROR, "", "Firebase konnte nicht starten", e.message.orEmpty())
        }
        changed()
    }

    // ---------------------------------------------------------------- Dienst

    fun onServiceConnected(s: SyncListenerService) {
        service = s
        journal.add(LogType.INFO, "", "Benachrichtigungszugriff verbunden", "")
        if (relay == null && pairing != null) applyPairing()
        evaluateAll()
        changed()
    }

    fun onServiceDisconnected(s: SyncListenerService) {
        if (service === s) service = null
        changed()
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (!relevant(sbn)) return
        markSeen(sbn.packageName)
        val touched = HashSet<Pair<String, String>>()
        evaluate(sbn, Snapshots.from(sbn), events.all(), touched)
        cleanupSummaries(touched)
    }

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        if (!relevant(sbn)) return
        val r = rules ?: return
        val snap = Snapshots.from(sbn)
        if (snap.isLive || snap.isGroupSummary) return
        markSeen(sbn.packageName)
        val title = snap.conversationTitle ?: snap.title.orEmpty()
        val app = appLabel(snap.packageName)

        if (!r.isReadSignal(reason)) {
            if (reason == RemovalReasons.CANCEL || reason == RemovalReasons.CANCEL_ALL || reason == RemovalReasons.APP_CANCEL_ALL) {
                journal.add(LogType.IGNORED, app, title, "${r.describeReason(reason)}: zählt nicht als gelesen")
                changed()
            }
            return
        }

        val readAt = System.currentTimeMillis()
        // Kurz warten: Manche Apps entfernen und posten neu, statt zu lesen.
        main.postDelayed({
            val active = service?.active().orEmpty().filter { it.packageName == snap.packageName }.map { Snapshots.from(it) }
            val event = r.buildReadEvent(snap, reason, readAt, active, UUID.randomUUID().toString())
            if (event == null) {
                val why = when {
                    snap.isRedacted -> "Inhalt von Android geschwärzt"
                    snap.isMessaging && snap.conversationId.isNullOrBlank() -> "Keine Chat-ID, kein sicherer Abgleich möglich"
                    else -> "Die App hat die Benachrichtigung gleich wieder angezeigt"
                }
                journal.add(LogType.IGNORED, app, title, "${r.describeReason(reason)}, aber: $why")
                changed()
                return@postDelayed
            }
            val rl = relay
            if (rl == null) {
                journal.add(LogType.ERROR, app, title, "Kein Relay verbunden, Meldung nicht gesendet")
                changed()
                return@postDelayed
            }
            rl.send(event) { ok, err ->
                journal.add(
                    if (ok) LogType.SENT else LogType.ERROR, app, title,
                    if (ok) "${r.describeReason(reason)}, an Partnergerät gemeldet" else "Senden fehlgeschlagen: $err",
                )
                changed()
            }
        }, SOURCE_DELAY_MS)
    }

    // ---------------------------------------------------------------- Relay

    override fun onRelayEvent(event: ReadEvent, sentAt: Long) {
        main.post {
            if (sentAt > prefs.getLong("since", 0)) prefs.edit().putLong("since", sentAt).apply()
            if (!events.add(event)) return@post
            journal.add(LogType.RECEIVED, appLabel(event.packageName), "", "Am Partnergerät gelesen (${if (event.reason.name == "OPENED") "geöffnet" else "in der App"})")
            evaluateAll()
            changed()
        }
    }

    override fun onRelayStatus(status: String) {
        main.post {
            relayStatus = status
            changed()
        }
    }

    // ---------------------------------------------------------------- Entscheiden und Entfernen

    private fun evaluateAll() {
        val svc = service ?: return
        val evs = events.all()
        if (evs.isEmpty()) return
        val touched = HashSet<Pair<String, String>>()
        for (sbn in svc.active()) {
            if (relevant(sbn)) evaluate(sbn, Snapshots.from(sbn), evs, touched)
        }
        cleanupSummaries(touched)
    }

    private fun evaluate(
        sbn: StatusBarNotification,
        snap: NotificationSnapshot,
        evs: List<ReadEvent>,
        touched: MutableSet<Pair<String, String>>,
    ) {
        val r = rules ?: return
        val title = snap.conversationTitle ?: snap.title.orEmpty()
        val app = appLabel(snap.packageName)
        when (val d = r.decide(snap, evs, System.currentTimeMillis())) {
            is Decision.NoMatch -> Unit
            is Decision.Keep -> {
                if (loggedDecisions.add("${sbn.key}|${sbn.postTime}|K|${d.reason}")) {
                    journal.add(LogType.KEPT, app, title, d.reason)
                    changed()
                }
            }
            is Decision.Remove -> {
                if (dryRun) {
                    if (loggedDecisions.add("${sbn.key}|${sbn.postTime}|R")) {
                        journal.add(LogType.WOULD_REMOVE, app, title, "Treffer über ${d.match}. Nichts entfernt.")
                        changed()
                    }
                    return
                }
                val svc = service ?: return
                val id = UUID.randomUUID().toString()
                trash.add(TrashEntry(id, System.currentTimeMillis(), snap.packageName, app, title, describe(snap)))
                sbn.notification.contentIntent?.let { contentIntents[id] = it }
                svc.cancel(sbn.key)
                snap.groupKey?.let { touched += snap.packageName to it }
                journal.add(LogType.REMOVED, app, title, "Treffer über ${d.match}. Liegt 24 h im Papierkorb.")
                changed()
            }
        }
    }

    private fun cleanupSummaries(touched: Set<Pair<String, String>>) {
        if (touched.isEmpty() || dryRun) return
        val svc = service ?: return
        val r = rules ?: return
        val snaps = svc.active().map { Snapshots.from(it) }
        r.orphanedSummaries(snaps, touched).forEach { svc.cancel(it.key) }
    }

    /** Öffnet eine Benachrichtigung aus dem Papierkorb: wie ein Tipp darauf, sonst die App. */
    fun openFromTrash(entry: TrashEntry): Boolean {
        contentIntents[entry.id]?.let { pi ->
            val ok = runCatching {
                val opts = ActivityOptions.makeBasic()
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            }.isSuccess
            if (ok) return true
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(entry.packageName) ?: return false
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun removeFromTrash(id: String) {
        trash.remove(id)
        contentIntents.remove(id)
        changed()
    }

    fun clearJournal() {
        journal.clear()
        loggedDecisions.clear()
        changed()
    }

    // ---------------------------------------------------------------- Hilfen

    private fun relevant(sbn: StatusBarNotification): Boolean =
        rules != null &&
            sbn.packageName != ctx.packageName &&
            sbn.packageName != "android" &&
            sbn.packageName !in excluded()

    private fun markSeen(pkg: String) {
        val seen = seenPackages()
        if (pkg !in seen) {
            prefs.edit().putStringSet("seen", seen + pkg).apply()
            changed()
        }
    }

    private fun describe(s: NotificationSnapshot): String =
        if (s.messages.isNotEmpty()) {
            s.messages.joinToString("\n") { m -> if (m.sender.isBlank()) m.text else "${m.sender}: ${m.text}" }
        } else {
            listOfNotNull(s.bigText ?: s.text, s.subText).joinToString("\n")
        }

    private fun changed() {
        _changes.value = _changes.value + 1
    }

    companion object {
        private const val PAIRING = "pairing"
        private const val CODE_PREFIX = "NSYNC1:"
        private const val SOURCE_DELAY_MS = 4_000L

        @Volatile
        private var instance: SyncEngine? = null

        fun get(context: Context): SyncEngine =
            instance ?: synchronized(this) {
                instance ?: SyncEngine(context.applicationContext).also { instance = it }
            }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
