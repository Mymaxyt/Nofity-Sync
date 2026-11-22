package com.notifysync.app.storage

import com.notifysync.core.EventKind
import com.notifysync.core.ReadEvent
import com.notifysync.core.ReadReason
import org.json.JSONArray
import org.json.JSONObject

/** Firebase-Zugangsdaten und Kopplungsgeheimnis. Liegen nur verschlüsselt im Vault. */
data class Pairing(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val secretHex: String,
) {
    fun toJson(): String = JSONObject()
        .put("p", projectId).put("a", appId).put("k", apiKey).put("s", secretHex)
        .toString()

    companion object {
        fun fromJson(s: String): Pairing = JSONObject(s).let {
            Pairing(it.getString("p"), it.getString("a"), it.getString("k"), it.getString("s"))
        }
    }
}

enum class LogType(val label: String) {
    SENT("Gemeldet"),
    RECEIVED("Empfangen"),
    WOULD_REMOVE("Probelauf: würde entfernen"),
    REMOVED("Entfernt"),
    KEPT("Stehen gelassen"),
    IGNORED("Nicht gemeldet"),
    INFO("Info"),
    ERROR("Fehler"),
}

data class LogEntry(val time: Long, val type: LogType, val app: String, val title: String, val detail: String)

data class TrashEntry(
    val id: String,
    val time: Long,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
)

/** Protokoll der letzten 7 Tage (max. 500 Einträge), verschlüsselt. */
class Journal(private val vault: Vault) {
    private val entries = ArrayList<LogEntry>()

    init {
        vault.read(FILE)?.let { raw ->
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries += LogEntry(
                    o.getLong("t"), runCatching { LogType.valueOf(o.getString("y")) }.getOrDefault(LogType.INFO),
                    o.optString("a"), o.optString("h"), o.optString("d"),
                )
            }
        }
    }

    @Synchronized
    fun add(type: LogType, app: String, title: String, detail: String) {
        entries += LogEntry(System.currentTimeMillis(), type, app, title.take(120), detail)
        prune()
        save()
    }

    @Synchronized
    fun all(): List<LogEntry> = entries.reversed()

    @Synchronized
    fun clear() {
        entries.clear()
        save()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 7 * DAY
        entries.removeAll { it.time < cutoff }
        while (entries.size > 500) entries.removeAt(0)
    }

    private fun save() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put("t", it.time).put("y", it.type.name).put("a", it.app).put("h", it.title).put("d", it.detail))
        }
        vault.write(FILE, arr.toString())
    }

    private companion object {
        const val FILE = "journal"
    }
}

/** "Zuletzt entfernt": jede entfernte Benachrichtigung bleibt 24 Stunden abrufbar. */
class Trash(private val vault: Vault) {
    private val entries = ArrayList<TrashEntry>()

    init {
        vault.read(FILE)?.let { raw ->
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries += TrashEntry(o.getString("i"), o.getLong("t"), o.getString("p"), o.optString("a"), o.optString("h"), o.optString("x"))
            }
        }
        prune()
    }

    @Synchronized
    fun add(e: TrashEntry) {
        entries += e
        prune()
        save()
    }

    @Synchronized
    fun all(): List<TrashEntry> {
        prune()
        return entries.reversed()
    }

    @Synchronized
    fun remove(id: String) {
        entries.removeAll { it.id == id }
        save()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - DAY
        entries.removeAll { it.time < cutoff }
    }

    private fun save() {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put("i", it.id).put("t", it.time).put("p", it.packageName).put("a", it.app).put("h", it.title).put("x", it.text))
        }
        vault.write(FILE, arr.toString())
    }

    private companion object {
        const val FILE = "trash"
    }
}

/** Vom Partnergerät empfangene Lese-Meldungen, 24 Stunden gültig (für spät eintreffende Pushs). */
class EventStore(private val vault: Vault) {
    private val events = LinkedHashMap<String, ReadEvent>()

    init {
        vault.read(FILE)?.let { raw ->
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hashes = o.getJSONArray("m").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val e = ReadEvent(
                    o.getString("i"), o.getString("f"), o.getString("p"), EventKind.valueOf(o.getString("k")),
                    o.optString("c").ifEmpty { null }, hashes, o.optString("h").ifEmpty { null },
                    o.getLong("r"), ReadReason.valueOf(o.getString("y")),
                )
                events[e.eventId] = e
            }
        }
    }

    /** @return false, wenn die Meldung schon bekannt war. */
    @Synchronized
    fun add(e: ReadEvent): Boolean {
        if (events.containsKey(e.eventId)) return false
        events[e.eventId] = e
        prune()
        save()
        return true
    }

    @Synchronized
    fun all(): List<ReadEvent> {
        prune()
        return events.values.toList()
    }

    @Synchronized
    fun clear() {
        events.clear()
        save()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - DAY
        events.values.removeAll { it.readAt < cutoff }
    }

    private fun save() {
        val arr = JSONArray()
        events.values.forEach {
            arr.put(
                JSONObject().put("i", it.eventId).put("f", it.fromDevice).put("p", it.packageName).put("k", it.kind.name)
                    .put("c", it.conversationHash ?: "").put("m", JSONArray(it.messageHashes.toList()))
                    .put("h", it.contentHash ?: "").put("r", it.readAt).put("y", it.reason.name),
            )
        }
        vault.write(FILE, arr.toString())
    }

    private companion object {
        const val FILE = "events"
    }
}

const val DAY = 24 * 60 * 60 * 1000L
