# Odtwarzacz MP3 — projekt Android (Kotlin + Media3)

Kompletny, gotowy do zbudowania projekt Android Studio. Nie wymaga pisania kodu —
wystarczy otworzyć projekt i uruchomić budowanie.

---

## 1. Co jest zaimplementowane

| Wymaganie | Realizacja |
|---|---|
| Odtwórz / pauza | `ExoPlayer.play()/pause()` + przycisk centralny |
| Następny / poprzedni | `seekToNext()` / `seekToPrevious()` (poniżej 3 s → utwór poprzedni, powyżej → początek bieżącego) |
| Wybór lokalizacji źródłowej | Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`, uprawnienie trwałe (`takePersistableUriPermission`), rekurencyjne skanowanie podkatalogów |
| Zapętlanie wszystkich utworów | `Player.REPEAT_MODE_ALL` (przycisk cykluje: wył. → wszystkie → jeden) |
| Odtwarzanie losowe | `shuffleModeEnabled` + `DefaultShuffleOrder(n, seed)` |
| Inna strategia losowa przy każdym uruchomieniu | Nowe ziarno (`System.nanoTime() xor Random.nextLong()`) generowane w `PlaybackService.applyNewShuffleOrder()`; poprzednie ziarno jest zapamiętywane i nigdy nie powtarzane bezpośrednio |
| Pamiętanie ostatniego utworu | `SharedPreferences`: `mediaId` (URI) + pozycja w ms; zapis cykliczny co 3 s oraz przy zmianie utworu / stanu |
| Praca w tle i przy zablokowanym ekranie | `MediaSessionService` (foreground service, `foregroundServiceType="mediaPlayback"`), `WAKE_MODE_LOCAL`, powiadomienie ze sterowaniem generowane przez Media3 |
| Orientacja zawsze pionowa | `android:screenOrientation="portrait"` w manifeście |

Dodatkowo: obsługa focusu audio (ściszenie/pauza przy połączeniu), pauza przy
odłączeniu słuchawek, pominięcie uszkodzonego pliku, pasek postępu z przewijaniem.

---

## 2. Budowanie — krok po kroku

### 2.1. Instalacja Android Studio
1. Pobierz **Android Studio** ze strony <https://developer.android.com/studio> (wersja Ladybug lub nowsza).
2. Zainstaluj z ustawieniami domyślnymi. Przy pierwszym uruchomieniu kreator pobierze
   **Android SDK Platform 34** i **Build-Tools** — zgódź się.

### 2.2. Otwarcie projektu
1. Android Studio → **Open**.
2. Wskaż katalog `Mp3Player` (ten, w którym leży plik `settings.gradle.kts`).
3. Poczekaj na **Gradle Sync** (pierwszy raz: 5–15 min, pobierane są biblioteki).
   Jeśli pojawi się monit o instalację brakującego SDK / Build-Tools — kliknij link
   „Install missing platform(s)".

### 2.3. Uruchomienie na telefonie
1. W telefonie: **Ustawienia → Informacje o telefonie** → 7× dotknij „Numer kompilacji”
   → włączone *Opcje programisty*.
2. **Opcje programisty → Debugowanie USB: włącz**.
3. Podłącz telefon kablem USB, zaakceptuj na telefonie komunikat o autoryzacji.
4. W Android Studio wybierz urządzenie z listy u góry i kliknij **Run ▶**.

### 2.4. Zbudowanie pliku APK (instalacja bez kabla)
- Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
- Plik powstanie w `app/build/outputs/apk/debug/app-debug.apk`.
- Skopiuj go na telefon i zainstaluj (wymaga zezwolenia na instalację z nieznanych źródeł).

### 2.5. Budowanie z wiersza poleceń (opcjonalnie)
```
gradlew.bat assembleDebug      (Windows)
./gradlew assembleDebug        (Linux/macOS)
```
Wymaga zmiennej `ANDROID_HOME` wskazującej na katalog SDK lub pliku `local.properties`
z wpisem `sdk.dir=C\:\\Users\\Adam\\AppData\\Local\\Android\\Sdk`.

---

## 3. Obsługa aplikacji

1. Pierwsze uruchomienie → dotknij **ikony folderu** w prawym górnym rogu.
2. Wskaż katalog z plikami MP3 (np. `Muzyka`). Aplikacja przeskanuje go rekurencyjnie.
3. Dotknięcie pozycji na liście = odtworzenie.
4. Przycisk **⤨** (po lewej) — tryb losowy; przycisk **⟳** (po prawej) — zapętlanie
   (wył. → wszystkie → jeden utwór).
5. Po zamknięciu i ponownym otwarciu aplikacja wczytuje ostatni utwór i pozycję
   (nie startuje automatycznie — wystarczy nacisnąć ▶).

---

## 4. Struktura kodu

```
app/src/main/java/pl/pk/mp3player/
├── MainActivity.kt      – UI: lista utworów, panel transportu, MediaController
├── PlaybackService.kt   – MediaSessionService + ExoPlayer, playlista, stan, shuffle
├── TrackScanner.kt      – rekurencyjne skanowanie drzewa SAF
├── TrackAdapter.kt      – adapter RecyclerView
└── Prefs.kt             – trwały stan (SharedPreferences)
```

Wymiana danych Activity ↔ Service odbywa się wyłącznie przez `MediaController`
(Media3), dzięki czemu interfejs, powiadomienie i ekran blokady zawsze pokazują
ten sam stan.

---

## 5. Uwagi techniczne i ograniczenia

- **Tytuły utworów pochodzą z nazw plików**, nie z tagów ID3. Odczyt tagów dla całego
  katalogu wymagałby `MediaMetadataRetriever` dla każdego pliku (kosztowne przy dużych
  bibliotekach). ExoPlayer i tak odczytuje tagi w trakcie odtwarzania i przekazuje je
  do powiadomienia.
- **minSdk 24** (Android 7.0), **targetSdk 34** (Android 14).
- Wersje: Android Gradle Plugin 8.5.2, Gradle 8.7, Kotlin 1.9.24, Media3 1.4.1.
  Jeśli Android Studio zaproponuje aktualizację AGP — można przyjąć, projekt nie
  używa API wycofanych w nowszych wersjach.
- Aplikacja nie żąda uprawnienia do całej pamięci masowej — dostęp opiera się na
  trwałym uprawnieniu SAF do wskazanego katalogu. Po zmianie katalogu stara
  playlista jest zastępowana.
- Na Androidzie 13+ przy pierwszym uruchomieniu pojawi się prośba o zgodę na
  powiadomienia. Odmowa nie blokuje odtwarzania, ale usuwa sterowanie z ekranu blokady.
