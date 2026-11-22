package com.notifysync.app.relay

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.notifysync.app.storage.DAY
import com.notifysync.app.storage.Pairing
import com.notifysync.core.EventCodec
import com.notifysync.core.PairingCrypto
import com.notifysync.core.ReadEvent
import java.util.Date

/**
 * Cloud-Relay über Firestore. Der Server sieht nur: Kanal-ID, Geräte-ID, Zeitstempel und ein
 * verschlüsseltes Paket. Schlüssel und Inhalte bleiben auf den Geräten.
 *
 * Firebase wird ohne google-services.json initialisiert; die drei Werte stehen in der Kopplung.
 */
class FirebaseRelay(
    context: Context,
    pairing: Pairing,
    private val crypto: PairingCrypto,
    private val deviceId: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onRelayEvent(event: ReadEvent, sentAt: Long)
        fun onRelayStatus(status: String)
    }

    private val app: FirebaseApp = run {
        val options = FirebaseOptions.Builder()
            .setProjectId(pairing.projectId)
            .setApplicationId(pairing.appId)
            .setApiKey(pairing.apiKey)
            .build()
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { existing ->
            if (existing.options == options) return@run existing
            existing.delete()
        }
        FirebaseApp.initializeApp(context, options, APP_NAME)
    }

    private val db = FirebaseFirestore.getInstance(app)
    private val auth = FirebaseAuth.getInstance(app)
    private var registration: ListenerRegistration? = null
    private var stopped = false

    private fun events() = db.collection("pairs").document(crypto.pairId).collection("events")

    fun start(since: Long) {
        listener.onRelayStatus("Anmelden …")
        if (auth.currentUser != null) {
            listen(since)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { if (!stopped) listen(since) }
                .addOnFailureListener {
                    listener.onRelayStatus("Anmeldung fehlgeschlagen: ${it.message}. Ist „Anonym“ in Firebase Authentication aktiviert?")
                }
        }
    }

    private fun listen(since: Long) {
        registration?.remove()
        registration = events()
            .whereGreaterThan("t", since)
            .orderBy("t")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    listener.onRelayStatus("Relay-Fehler: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                listener.onRelayStatus(if (snap.metadata.isFromCache) "Offline, wartet auf Verbindung" else "Verbunden")
                for (change in snap.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    if (doc.getString("d") == deviceId) continue
                    val blob = doc.getBlob("c") ?: continue
                    val sentAt = doc.getLong("t") ?: 0L
                    try {
                        val event = EventCodec.decode(crypto.decrypt(blob.toBytes()))
                        listener.onRelayEvent(event, sentAt)
                    } catch (e: Exception) {
                        listener.onRelayStatus("Ein Paket konnte nicht entschlüsselt werden und wurde verworfen")
                    }
                }
            }
    }

    fun send(event: ReadEvent, onResult: (ok: Boolean, error: String?) -> Unit) {
        val now = System.currentTimeMillis()
        val data = mapOf(
            "d" to deviceId,
            "t" to now,
            "c" to Blob.fromBytes(crypto.encrypt(EventCodec.encode(event))),
            // Für eine optionale TTL-Regel in Firestore: Pakete nach 2 Tagen automatisch löschen.
            "x" to Timestamp(Date(now + 2 * DAY)),
        )
        events().document(event.eventId).set(data)
            .addOnSuccessListener { onResult(true, null) }
            .addOnFailureListener { onResult(false, it.message) }
    }

    fun stop() {
        stopped = true
        registration?.remove()
        registration = null
    }

    private companion object {
        const val APP_NAME = "notifysync"
    }
}
