# Soundboard

Einfache Android-Soundboard-App (Kotlin, min. Android 8.0 / API 26).

## Funktionen

- 12 Sound-Buttons im Raster (3 Spalten)
- Pro Button: MP3-/Audiodatei hinterlegen (per Dateiauswahl, URI wird persistent gespeichert)
- Pro Button einstellbar:
  - **Abspieldauer in Sekunden** (`0` = komplette Datei)
  - **Hart abbrechen** oder **smooth ausblenden** (Fade-Out ueber die letzten 500 ms)
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

`SoundPlayer` nutzt einen Watchdog (alle 50 ms), der die Restzeit berechnet
(min aus eingestellter Dauer und Dateilaenge). Bei gesetztem Dauerlimit wird
darauf hart gestoppt; ist "Smooth ausblenden" aktiv, startet in den letzten
500 ms ein Fade-Out (Lautstaerke-Rampe auf 0) und danach Stop + Fokusfreigabe.

## Build

1. Projekt in **Android Studio** oeffnen (laedt Gradle-Wrapper automatisch)
2. Auf Geraet mit Android 8.0+ deployen (Run-Button)
3. MP3s z. B. unter `Downloads` ablegen und in der App auswaehlen
4. Release-APK (minifiziert via R8 + Ressource-Shrinking): `gradle :app:assembleRelease` oder Android Studio: Build > Generate Signed Bundle / APK

## Projektstruktur

- `MainActivity.kt` – UI, Dateiauswahl, Bearbeiten-Dialog, Persistenz
- `SoundPlayer.kt` – MediaPlayer + AudioFocus + Dauerlimit/Fade-Out
- `SoundStore.kt` – Speicherung der Konfiguration in SharedPreferences (JSON)
- `SoundAdapter.kt` – RecyclerView-Adapter fuer die Button-Kacheln
