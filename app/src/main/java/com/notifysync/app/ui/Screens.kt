package com.notifysync.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.notifysync.app.SyncEngine
import com.notifysync.app.service.SyncListenerService
import com.notifysync.app.storage.LogType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liest den Änderungszähler der Engine als Compose-State. Jeder Bildschirm ruft das auf, damit er
 * neu gezeichnet wird, sobald sich in der Engine etwas ändert (z. B. nach dem Koppeln).
 */
@Composable
private fun SyncEngine.observe(): Int = changes.collectAsState().value

private val TABS = listOf("Status", "Kopplung", "Protokoll", "Papierkorb", "Apps")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifySyncApp(resumeTick: Int) {
    val ctx = LocalContext.current
    val engine = remember { SyncEngine.get(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(if (engine.pairing == null) 1 else 0) }

    MaterialTheme(colorScheme = dynamicLightColorScheme(ctx)) {
        Scaffold(topBar = { TopAppBar(title = { Text("Notify-Sync (Test)") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> StatusScreen(engine, resumeTick)
                    1 -> PairingScreen(engine)
                    2 -> JournalScreen(engine)
                    3 -> TrashScreen(engine)
                    else -> AppsScreen(engine)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Status

@Composable
private fun StatusScreen(engine: SyncEngine, resumeTick: Int) {
    val ctx = LocalContext.current
    val version = engine.observe()
    val listenerOk = remember(resumeTick, version) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val batteryOk = remember(resumeTick, version) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    var confirmLive by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepCard(
            done = engine.pairing != null,
            title = "1. Geräte koppeln",
            body = if (engine.pairing != null) "Gekoppelt. Prüfcode: ${engine.verificationCode()}" else "Im Reiter „Kopplung“ einrichten.",
        )
        StepCard(
            done = listenerOk,
            title = "2. Zugriff auf Benachrichtigungen",
            body = if (listenerOk) {
                if (engine.isServiceConnected) "Erteilt und verbunden." else "Erteilt, Dienst startet …"
            } else {
                "Ohne diesen Zugriff sieht die App nichts. Falls der Schalter grau ist: zuerst unten „Eingeschränkte Einstellungen zulassen“."
            },
        ) {
            Button(onClick = {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, SyncListenerService::class.java).flattenToString())
                runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }) { Text("Zugriff öffnen") }
            OutlinedButton(onClick = { openAppInfo(ctx) }) { Text("App-Info (⋮ → Eingeschränkte Einstellungen zulassen)") }
        }
        StepCard(
            done = batteryOk,
            title = "3. Samsung-Akku: nicht einschränken",
            body = "One UI beendet Hintergrund-Apps gern. Auf beiden Geräten: Akku → „Nicht eingeschränkt“. " +
                "Zusätzlich in Einstellungen → Akku → Hintergrund-Nutzungslimits → „Nie in den Ruhezustand versetzte Apps“ Notify-Sync hinzufügen.",
        ) {
            if (!batteryOk) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Akku-Optimierung ausschalten") }
            }
            OutlinedButton(onClick = { openAppInfo(ctx) }) { Text("App-Info öffnen") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Probelauf", fontWeight = FontWeight.Bold)
                        Text(
                            if (engine.dryRun) "An: Es wird nur protokolliert, was entfernt würde. Nichts verschwindet."
                            else "Aus: Gelesene Benachrichtigungen werden wirklich entfernt (24 h im Papierkorb).",
                        )
                    }
                    Switch(checked = engine.dryRun, onCheckedChange = { on -> if (on) engine.dryRun = true else confirmLive = true })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Detailprotokoll", fontWeight = FontWeight.Bold)
                        Text("Jede Benachrichtigung mit allen Eigenschaften (App, Kanal, Chat-ID, Flags, Grund des Verschwindens …) im Protokoll.")
                    }
                    Switch(checked = engine.detailLog, onCheckedChange = { engine.detailLog = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Inhalte mitprotokollieren", fontWeight = FontWeight.Bold)
                        Text("Aus: nur Länge der Texte. An: Titel und Nachrichtentexte stehen im Protokoll und beim Teilen in der Datei.")
                    }
                    Switch(checked = engine.logContent, onCheckedChange = { engine.logContent = it })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cloud-Relay", fontWeight = FontWeight.Bold)
                Text(engine.relayStatus)
                Text("Geräte-ID: ${engine.deviceId}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            "Regeln dieser Testversion: Entfernt wird nur, was am anderen Gerät geöffnet oder in der App gelesen wurde. " +
                "Wegwischen zählt nicht. Enthält die Benachrichtigung hier eine neuere Nachricht, bleibt sie stehen. " +
                "Anrufe, Medien, Navigation, Fortschritt und Timer werden nie angefasst.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("Probelauf beenden?") },
            text = { Text("Hast du im Protokoll geprüft, dass nur wirklich gelesene Benachrichtigungen als „würde entfernen“ markiert waren? Danach werden sie tatsächlich entfernt.") },
            confirmButton = { TextButton(onClick = { engine.dryRun = false; confirmLive = false }) { Text("Ja, scharf schalten") } },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StepCard(done: Boolean, title: String, body: String, actions: @Composable () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (done) Color(0xFFE3F2E6) else Color(0xFFFFF4E0)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (done) "✓ " else "• ") + title, fontWeight = FontWeight.Bold)
            Text(body)
            actions()
        }
    }
}

private fun openAppInfo(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

// ------------------------------------------------------------------------------------ Kopplung

@Composable
private fun PairingScreen(engine: SyncEngine) {
    engine.observe()
    val ctx = LocalContext.current
    var projectId by rememberSaveable { mutableStateOf("") }
    var appId by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var manualCode by rememberSaveable { mutableStateOf("") }
    var confirmUnpair by remember { mutableStateOf(false) }

    fun join(code: String) {
        runCatching { engine.joinPairing(code) }
            .onSuccess { Toast.makeText(ctx, "Gekoppelt. Prüfcode vergleichen!", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(ctx, it.message ?: "Ungültiger Code", Toast.LENGTH_LONG).show() }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { join(it) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val code = engine.pairingCode()
        if (code != null) {
            Text("Gekoppelt", style = MaterialTheme.typography.titleMedium)
            Text("Prüfcode: ${engine.verificationCode()}", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Text("Auf beiden Geräten muss derselbe Prüfcode stehen.")
            Text("Zweites Gerät koppeln: dort „QR-Code scannen“ wählen und diesen Code scannen.")
            val qr = remember(code) { Qr.bitmap(code) }
            Image(qr.asImageBitmap(), contentDescription = "Kopplungs-QR-Code", modifier = Modifier.size(280.dp).align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Notify-Sync", code))
                Toast.makeText(ctx, "Code kopiert. Nur an dein eigenes Gerät weitergeben.", Toast.LENGTH_LONG).show()
            }) { Text("Code als Text kopieren") }
            Text("Der Code enthält den geheimen Schlüssel. Nicht teilen.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Kopplung aufheben") }
        } else {
            Text("Erstes Gerät (z. B. Handy)", style = MaterialTheme.typography.titleMedium)
            Text("Werte aus der Firebase-Konsole → Projekteinstellungen → Allgemein → deine Android-App.")
            OutlinedTextField(projectId, { projectId = it }, label = { Text("Projekt-ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(appId, { appId = it }, label = { Text("App-ID (1:…:android:…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Web-API-Schlüssel (AIza…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank(),
                onClick = {
                    runCatching { engine.createPairing(projectId, appId, apiKey) }
                        .onFailure { Toast.makeText(ctx, "Kopplung fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show() }
                },
            ) { Text("Kopplung erstellen") }

            Spacer(Modifier.height(16.dp))
            Text("Zweites Gerät (z. B. Tablet)", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("QR-Code vom ersten Gerät scannen").setBeepEnabled(false).setOrientationLocked(false),
                )
            }) { Text("QR-Code scannen") }
            OutlinedTextField(manualCode, { manualCode = it }, label = { Text("… oder Code einfügen (NSYNC1:…)") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = manualCode.isNotBlank(), onClick = { join(manualCode) }) { Text("Mit Code koppeln") }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Kopplung aufheben?") },
            text = { Text("Die Schlüssel werden auf diesem Gerät gelöscht. Das andere Gerät muss danach neu gekoppelt werden.") },
            confirmButton = { TextButton(onClick = { engine.unpair(); confirmUnpair = false }) { Text("Aufheben") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Abbrechen") } },
        )
    }
}

// ------------------------------------------------------------------------------------ Protokoll

private val timeFormat = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

@Composable
private fun JournalScreen(engine: SyncEngine) {
    engine.observe()
    val ctx = LocalContext.current
    val entries = engine.journal.all()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} Einträge (7 Tage)", modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Notify-Sync Protokoll")
                    .putExtra(Intent.EXTRA_TEXT, engine.journalAsText())
                ctx.startActivity(Intent.createChooser(send, "Protokoll teilen"))
            }) { Text("Teilen") }
            TextButton(onClick = { engine.clearJournal() }) { Text("Leeren") }
        }
        if (entries.isEmpty()) {
            Text("Noch nichts passiert.", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { e ->
                val color = when (e.type) {
                    LogType.WOULD_REMOVE -> Color(0xFF1E5AA8)
                    LogType.REMOVED -> Color(0xFF2E7D32)
                    LogType.KEPT -> Color(0xFF8D6E00)
                    LogType.ERROR -> Color(0xFFC62828)
                    LogType.DETAIL_POSTED, LogType.DETAIL_REMOVED -> Color(0xFF6B6B6B)
                    else -> Color.Unspecified
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${timeFormat.format(Date(e.time))} · ${e.type.label}", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    val head = listOf(e.app, e.title).filter { it.isNotBlank() }.joinToString(" · ")
                    if (head.isNotBlank()) Text(head)
                    if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Papierkorb

@Composable
private fun TrashScreen(engine: SyncEngine) {
    engine.observe()
    val ctx = LocalContext.current
    val entries = engine.trash.all()
    Column(Modifier.fillMaxSize()) {
        Text("Zuletzt entfernt (24 Stunden)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) Text("Leer.", Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { e ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${timeFormat.format(Date(e.time))} · ${e.app}", style = MaterialTheme.typography.bodySmall)
                        if (e.title.isNotBlank()) Text(e.title, fontWeight = FontWeight.Bold)
                        if (e.text.isNotBlank()) Text(e.text)
                        Row {
                            TextButton(onClick = {
                                if (!engine.openFromTrash(e)) Toast.makeText(ctx, "App konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
                            }) { Text("Öffnen") }
                            TextButton(onClick = { engine.removeFromTrash(e.id); Toast.makeText(ctx, "Aus Papierkorb gelöscht", Toast.LENGTH_SHORT).show() }) { Text("Löschen") }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ Apps

@Composable
private fun AppsScreen(engine: SyncEngine) {
    engine.observe()
    val excluded = engine.excluded()
    val apps = engine.seenPackages().map { it to engine.appLabel(it) }.sortedBy { it.second.lowercase() }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Alle Apps werden abgeglichen. Hier erscheinen Apps, sobald sie eine Benachrichtigung geschickt haben. Schalter aus = nie abgleichen.",
            Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(apps) { (pkg, label) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        Text(pkg, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = pkg !in excluded, onCheckedChange = { on -> engine.setExcluded(pkg, !on) })
                }
            }
        }
    }
}
