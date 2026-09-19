# Budowanie APK bez instalowania Android Studio

Projekt zawiera gotową konfigurację budowania w chmurze — plik **`build-apk.yml`**
leżący w katalogu głównym projektu. W repozytorium GitHub musi on trafić pod ścieżkę
`.github/workflows/build-apk.yml` (katalogu `.github` nie dało się zapisać
bezpośrednio na dysku — jest chroniony przez system).
Poniżej procedura od zera do pliku APK na telefonie — całość w przeglądarce.

## Wariant A — GitHub Actions (zalecany, darmowy)

1. Załóż konto na <https://github.com> (darmowe).
2. **New repository** → nazwa np. `mp3player` → widoczność **Public**
   (dla repozytoriów publicznych czas budowania jest nielimitowany i bezpłatny;
   prywatne mają 2000 min/mies. na planie Free — budowa tego projektu zajmuje ok. 3–5 min).
3. Wgraj pliki projektu: **Add file → Upload files**, przeciągnij zawartość
   katalogu `Mp3Player` (pliku `build-apk.yml` z katalogu głównego nie wgrywaj —
   patrz punkt 4).
4. Utwórz konfigurację budowania: **Add file → Create new file**, w polu nazwy
   wpisz dokładnie `.github/workflows/build-apk.yml` (GitHub sam utworzy
   katalogi), wklej całą treść pliku `build-apk.yml` z katalogu projektu
   i kliknij **Commit changes**.
5. Zakładka **Actions** → przepływ *Budowanie APK* uruchomi się automatycznie.
6. Po ok. 3–6 min wejdź w zakończone uruchomienie → sekcja **Artifacts** →
   pobierz `odtwarzacz-mp3-debug-apk.zip`, rozpakuj, przenieś `app-debug.apk`
   na telefon i zainstaluj (wymaga zgody na instalację z nieznanych źródeł).

Każda kolejna zmiana kodu wgrana do repozytorium automatycznie generuje nowy APK.

## Wariant B — GitHub Codespaces (pełne środowisko w przeglądarce)

Repozytorium → **Code → Codespaces → Create codespace**. Otrzymujesz maszynę
z Linuksem i edytorem VS Code w przeglądarce. Android SDK trzeba doinstalować
poleceniem `sdkmanager`, więc jest to rozwiązanie dla osoby, która chce także
edytować kod online. Plan darmowy: 120 rdzeniogodzin i 15 GB/mies.

## Wariant C — Codemagic

<https://codemagic.io> — konto indywidualne otrzymuje 500 darmowych minut
miesięcznie (maszyny macOS M2). Konfiguracja przez interfejs graficzny,
bez pliku YAML. Sensowne, jeśli GitHub Actions z jakiegoś powodu nie wchodzi
w grę; dla Androida jest to jednak droga okrężna.

## Czego NIE da się użyć

- **OnlineGDB, Replit, Programiz, JDoodle** i podobne „kompilatory online" —
  kompilują pojedyncze pliki konsolowe. Nie mają Android SDK, AAPT2, D8 ani
  Gradle; projektu Android nie zbudują.
- **AIDE** (IDE na telefonie) — nie obsługuje nowoczesnych projektów
  Gradle Kotlin DSL z bibliotekami AndroidX/Media3.
