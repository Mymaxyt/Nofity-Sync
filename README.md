# Notify-Sync (Test-App)

Android-App für Handy und Tablet: Was auf einem Gerät **wirklich gelesen** wurde, verschwindet
auf dem anderen. Erste Testversion, nur für den eigenen Gebrauch, nicht im Play Store.

## Was die Testversion tut

- Läuft auf beiden Geräten gleich (Android 13 bis Android 16, Ziel-SDK 36).
- Erkennt, wenn eine Benachrichtigung **geöffnet** oder **in der App gelesen** wurde
  (z. B. WhatsApp-Chat geöffnet), und meldet das verschlüsselt ans Partnergerät.
- **Wegwischen zählt nicht.** „Alle löschen“ auch nicht.
- Das Partnergerät entfernt nur, wenn alle Bedingungen erfüllt sind:
  1. gleicher Chat (Chat-ID der App) und gleiche Nachrichten,
  2. keine Nachricht darin ist neuer als der Lesezeitpunkt oder am anderen Gerät ungelesen,
  3. die Benachrichtigung ist keine Live-Benachrichtigung (Anruf, Medien, Navigation,
     Fortschritt, Timer, Wecker, Vordergrunddienst) und nicht von Android geschwärzt.
- Jede entfernte Benachrichtigung liegt **24 Stunden im Papierkorb** und lässt sich öffnen.
- **Probelauf ist ab Werk an:** Die App protokolliert nur, was sie entfernen würde.
- Alle Apps sind an; einzelne Apps lassen sich im Reiter „Apps“ ausnehmen.

Inhalte verlassen das Gerät nie. Übertragen werden nur gesalzene Hashes, verschlüsselt mit
AES-256-GCM. Der Schlüssel wird per QR-Code direkt von Gerät zu Gerät übergeben.

## Einrichtung

### 1. Firebase-Projekt (einmalig, ca. 10 Minuten)

1. <https://console.firebase.google.com> → **Projekt hinzufügen**, z. B. „notify-sync-test“.
   Google Analytics kann aus bleiben.
2. **Build → Authentication → Jetzt starten → Anmeldemethode → Anonym** aktivieren.
3. **Build → Firestore Database → Datenbank erstellen**, Standort **eur3 (Europa)**,
   Produktionsmodus. Danach im Reiter **Regeln** den Inhalt von [`firestore.rules`](firestore.rules)
   einfügen und **Veröffentlichen**.
4. **Projektübersicht → Android-App hinzufügen**, Paketname `com.notifysync.app`.
   Die `google-services.json` wird **nicht** gebraucht.
5. In **Projekteinstellungen → Allgemein** stehen die drei Werte, die die App beim Koppeln
   abfragt: **Projekt-ID**, **App-ID** (`1:…:android:…`) und **Web-API-Schlüssel** (`AIza…`).
6. Optional: Firestore → **TTL-Richtlinien** → Sammlungsgruppe `events`, Feld `x`.
   Dann löscht Firebase alte Pakete nach 2 Tagen selbst.

Im kostenlosen Spark-Tarif bleibt der Test weit unter den Grenzen.

### 2. App installieren (Handy und Tablet)

1. APK aus dem GitHub-Actions-Lauf „APK bauen“ herunterladen (Artefakt `notify-sync-apk`)
   oder selbst bauen: `./gradlew :app:assembleDebug`.
2. Auf dem Samsung-Gerät öffnen und „Unbekannte Apps installieren“ für den Browser bzw.
   „Eigene Dateien“ erlauben.
3. **Wichtig ab Android 13:** Bei nicht aus dem Store installierten Apps ist der
   Benachrichtigungszugriff gesperrt. Lösung: **Einstellungen → Apps → Notify-Sync → ⋮ (oben rechts)
   → Eingeschränkte Einstellungen zulassen.** Der Reiter „Status“ hat dafür einen Knopf.

### 3. Koppeln

1. **Handy:** Reiter „Kopplung“ → die drei Firebase-Werte eintragen → „Kopplung erstellen“.
   Es erscheint ein QR-Code.
2. **Tablet:** Reiter „Kopplung“ → „QR-Code scannen“.
3. Auf beiden Geräten muss derselbe **6-stellige Prüfcode** stehen.

### 4. Auf beiden Geräten im Reiter „Status“

- Zugriff auf Benachrichtigungen erteilen.
- Akku-Optimierung ausschalten. Zusätzlich bei Samsung: **Einstellungen → Akku →
  Hintergrund-Nutzungslimits → Nie in den Ruhezustand versetzte Apps → Notify-Sync** hinzufügen.

## Testplan

1. **Probelauf (Standard, die ersten Tage):** WhatsApp normal nutzen. Im Reiter „Protokoll“ des
   Tablets steht bei jeder Entscheidung „Probelauf: würde entfernen“ oder „Stehen gelassen“ mit Grund.
   Prüfen, dass **nie** eine ungelesene Nachricht als „würde entfernen“ auftaucht.
2. Gezielt testen:
   - Chat am Handy über die Benachrichtigung öffnen → Tablet: „würde entfernen“.
   - Benachrichtigung am Handy nur wegwischen → Handy: „Nicht gemeldet“, Tablet: nichts.
   - Chat am Handy lesen, dann sofort eine neue Nachricht im selben Chat bekommen →
     Tablet: „Stehen gelassen: … nach dem Lesen eingegangen“.
   - Anruf, Musik, Navigation → tauchen im Protokoll gar nicht auf.
3. Erst wenn das passt: im Reiter „Status“ den Probelauf ausschalten (auf dem Gerät, das entfernen soll).

## Bekannte Grenzen dieser Testversion

- **WhatsApp „in der App gelesen“:** Android meldet nur, dass WhatsApp die Benachrichtigung selbst
  entfernt hat. Manche Apps tun das auch schon beim bloßen Öffnen der App, ohne dass jeder Chat
  gelesen wurde. Genau dafür ist der Probelauf da; falls das bei WhatsApp passiert, wird das
  Signal „in der App gelesen“ für WhatsApp abgeschaltet und nur „geöffnet“ gezählt.
- Liefert eine App keine Chat-ID, wird bei Chats nichts entfernt (Protokoll: „Keine Chat-ID“).
- Nicht-Chat-Apps (z. B. Gmail) werden nur bei exakt gleichem Inhalt abgeglichen.
- Läuft nur mit genau zwei Geräten.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `core/` | Reine Kotlin-Logik ohne Android: Sicherheitsregeln, Fingerabdrücke, Verschlüsselung, Tests |
| `app/…/service/` | `NotificationListenerService`, Erkennung von Live-Benachrichtigungen |
| `app/…/relay/` | Firestore-Relay (nur verschlüsselte Pakete) |
| `app/…/storage/` | Mit Android-Keystore verschlüsselte Ablage: Kopplung, Protokoll, Papierkorb |
| `app/…/ui/` | Oberfläche (Jetpack Compose): Status, Kopplung, Protokoll, Papierkorb, Apps |

Tests der Abgleich-Logik: `./gradlew :core:test` (ohne Android SDK: `-PcoreOnly=true`).

`app/test-signing.keystore` ist ein reiner Test-Signaturschlüssel (Passwort `android`), damit
neue Testversionen sich über die alte installieren lassen. Für eine Veröffentlichung wird ein
eigener, geheimer Schlüssel gebraucht.
