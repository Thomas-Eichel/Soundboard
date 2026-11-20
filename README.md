# Soundboard

Einfache Android-Soundboard-App (Kotlin, min. Android 8.0 / API 26).

## Funktionen

- Sound-Buttons im Raster (**3 Spalten**, per **Zwei-Finger-Zoom** umschaltbar auf 3/4/5 Spalten, wird gespeichert), beliebig viele Kacheln: ueber das Menue **Kachel hinzufuegen**, per **Löschen** im Bearbeiten-Dialog entfernen, per **Drag & Drop** (Griff rechts oben) verschieben — die Reihenfolge wird persistiert und das Verschieben kann im Menue **gesperrt** werden
- **Überschriften** als Gruppentrenner: Menue **Überschrift hinzufügen** legt ein unauffälliges, flaches Titel-Feld über die volle Breite an (nur etwa so hoch wie die Schrift; Text per Tap editierbar, verschiebbar/löschbar, wird persistiert und im Profil-ZIP mitgeführt)
- Pro Button: MP3-/Audiodatei hinterlegen (per Dateiauswahl **oder per Aufnahme-Button** im Bearbeiten-Dialog — beim Start wählbar: **Mikrofon** oder **Geräte-Audio** (abgespielter Sound); Geräte-Audio nutzt ab Android 10 `MediaProjection` (Consent-Dialog; `RecordingService` als Foreground-Service Typ `mediaProjection` holt das Projection-Token und liest das Geräte-Audio per `AudioRecord` mit `AudioPlaybackCaptureConfiguration` als PCM/WAV, Dateiendung `.mp3`), darunter `REMOTE_SUBMIX` mit AAC/MPEG-4; die Aufnahme wird als Datei gespeichert, da Android keinen MP3-Encoder mitbringt)
- Pro Button einstellbar:
  - **Ausschnitt waehlbar**: Wellenform im Bearbeiten-Dialog, Anfang/Ende ueber zwei Schieberegler (`startSec`/`endSec` in Sekunden, `0` = Dateianfang bzw. Dateiende)
  - **Fade-In/Fade-Out** ueber zwei weitere Schieberegler nebeneinander (`fadeInSec`/`fadeOutSec` in Sekunden, `0` = aus / hart stoppen)
- Tap auf belegtes Feld = abspielen; Tap auf leeres Feld oder langer Tap = bearbeiten
- Menuepunkt **Alle stoppen**

## Sound ueber laufende Musik legen (Deezer & Co.)

Beim Abspielen wird **transienter Audio-Fokus** angefordert
(`AudioManager.AUDIOFOCUS_GAIN_TRANSIENT`, siehe `SoundPlayer.kt`).
 Dadurch pausiert die Musik-App (z. B. Deezer) waehrend des Sounds.
Sobald der Sound endet, hart abgebrochen oder ausgeblendet wird, wird der
Fokus freigegeben (`abandonAudioFocusRequest`) und die Musik-App spielt an der
alten Stelle weiter. Wird der Fokus anderweitig entzogen (z. B. Anruf), stoppt
der Sound sofort; bei `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` wird nur die
Lautstaerke reduziert.

## Abspiel-Logik

`SoundPlayer` nutzt einen Watchdog (alle 50 ms), der die Restzeit bis zum
gewaehlten Ende (`endSec`, sonst Dateiende) berechnet. Bei `fadeOutSec > 0`
startet im letzten Abschnitt ein Fade-Out (Lautstaerke-Rampe auf 0) und danach
Stop + Fokusfreigabe; bei `fadeInSec > 0` wird nach dem Start auf `startSec`
von 0 hochgeblendet.

## Build

1. Projekt in **Android Studio** oeffnen (laedt Gradle-Wrapper automatisch)
2. Auf Geraet mit Android 8.0+ deployen (Run-Button)
3. MP3s z. B. unter `Downloads` ablegen und in der App auswaehlen
4. Release-APK (minifiziert via R8 + Ressource-Shrinking): `gradle :app:assembleRelease` oder Android Studio: Build > Generate Signed Bundle / APK
5. Fertige install-faehige APK (debug-signiert): `dist/Soundboard-v1.0.0-debug.apk` im Repo bzw. als CI-Artefakt (`apks`) unter Actions

## Einstellungen, Kategorien und Profile

- Menue **Kategorien** (`SettingsActivity`): zentrale Liste der Kategorien verwalten (hinzufuegen / umbenennen / loeschen / per ▲▼ umsortieren — die Reihenfolge wird gespeichert und ueberall uebernommen; Aenderungen an Namen werden auf alle Sounds uebernommen)
- Pro Sound koennen im Bearbeiten-Dialog **zwei Kategorien** aus der zentralen Liste vergeben werden; sie werden auf der Kachel angezeigt
- **Kachel-Design pro Sound**: Akzentfarbe (kompakte Preset-Farbwahl) oder Grafikdatei (Bild); Kategorie-Texte auf der Kachel immer weiss; im Profil-ZIP enthalten (`backgroundColor` + `image/<slot>.bin`)
- Menue **Profil exportieren / Profil laden**: komplettes Profil als eine ZIP-Datei (`soundboard-profil.zip`) ueber den System-Dateidialog. Inhalt: `profile.json` (Version, Einstellungen inkl. `reorderLocked`, alle Kacheln in Reihenfolge mit Name/Fade/Kategorien/Ausschnitt `startSec`/`endSec`/`fadeInSec`/`fadeOutSec`/Design) + `audio/<slot>.mp3` (eingebettete Audiodaten). Beim Laden werden die MP3s ins app-private Verzeichnis entpackt — das Profil ist damit geraeteuebergreifend nutzbar
- Gerade abgespielter Sound wird auf seiner Kachel farblich hervorgehoben (Rand)

## Projektstruktur

- `MainActivity.kt` – UI, Dateiauswahl, Bearbeiten-Dialog, Profil-Import/Export
- `SettingsActivity.kt` – zentrale Kategorien
- `ProfileStore.kt` – Profil als ZIP (JSON + eingebettete MP3s) exportieren/laden
- `SoundPlayer.kt` – MediaPlayer + AudioFocus + Ausschnitt (startSec/endSec) + Fade-In/Fade-Out
- `SoundStore.kt` – Speicherung von Sounds + Einstellungen in SharedPreferences (JSON)
- `SoundAdapter.kt` – RecyclerView-Adapter fuer die Button-Kacheln inkl. Kategorie-Anzeige
