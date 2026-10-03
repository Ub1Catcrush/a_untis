# A*Untis – Android (Kotlin)

> Formerly "WebUntis Dashboard". **Current version: 0.5.4**

> **[Deutsch](#deutsch)** | **[English](#english)**

![A*Untis Screenshot](screenshot.jpg)

---

<a name="deutsch"></a>
## Deutsch

Native Kotlin Android-App für WebUntis-Schüler und Eltern mit Stundenplan, Unterrichtsinhalten, Hausaufgaben, Nachrichten, Abwesenheiten, Terminen und Klassenbuch – inklusive Unterstützung für mehrere Kinder und Hintergrund-Benachrichtigungen.

### Voraussetzungen

| Tool | Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ (GitHub-Workflow baut mit JDK 21) |
| Android SDK | API 26+ (minSdk) / API 36 (compileSdk / targetSdk) |
| Kotlin | 2.1.20 |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 9.4.1 (über den Gradle-Wrapper) |

### Projekt öffnen

1. Repository klonen oder ZIP entpacken
2. Android Studio → **Open** → Projektordner wählen
3. Gradle-Sync abwarten
4. Gerät / Emulator auswählen → **▶ Run**

### Anmeldung

| Feld | Beispiel |
|---|---|
| Server | `meine-schule.webuntis.com` |
| Schul-Kurzname | steht in der WebUntis-URL nach `/WebUntis/` |
| Benutzername | dein WebUntis-Login |
| Passwort | dein WebUntis-Passwort |

> **Hinweis:** Falls deine Schule SSO (Microsoft, Google, Schulportal) nutzt, werden trotzdem direkte WebUntis-Zugangsdaten benötigt. Diese sind separat über die WebUntis-Webseite einzurichten.

Zugangsdaten werden mit **EncryptedSharedPreferences** (AES-256) lokal gespeichert und verlassen das Gerät nicht (außer zur Anmeldung am eigenen WebUntis-Server). Bei Eltern-Accounts mit mehreren Kindern erscheint nach der Anmeldung eine Auswahl des Kindes.

#### Mehrere Kinder / Accounts

In den Einstellungen können beliebig viele **weitere Kinder** (jeweils mit eigenem Login und frei wählbarem Anzeigenamen) hinzugefügt werden – z. B. als Elternteil zusätzlich zum Haupt-Account. Auch der Haupt-Account kann einen Anzeigenamen erhalten. Über den **Kinder-Umschalter** in der Titelleiste wechselst du zwischen den Profilen; Stundenplan, Hausaufgaben, Abwesenheiten usw. zeigen dann die Daten des gewählten Kindes. Die Nachrichten aller Accounts werden in einem gemeinsamen Posteingang zusammengeführt (mit Account-Kennzeichnung).

### Navigation

Scrollbare untere Navigationsleiste mit allen Bereichen direkt erreichbar: **Stundenplan**, **Unterrichtsinhalte**, **Hausaufgaben**, **Nachrichten**, **Abwesenheiten**, **Termine**, **Klassenbuch** und **Einstellungen**.

### Screens & Funktionen

| Screen | Beschreibung |
|---|---|
| **Stundenplan** | Konfigurierbare Anzahl Schultage (1–20) mit Vertretungs-Info („statt …“), Unterrichtsinhalt, angepinnten Lehrer-Notizen und farbigen Status-Badges (Ausfall, Vertretung, Zusatz, Prüfung). Umschaltbar zwischen **eigenem Plan**, **Klassenstundenplan** und **kombiniertem Stundenplan** (eigener Plan, in dem freie Stunden mit frei wählbaren Klassenfächern aufgefüllt werden) sowie zwischen **Tagesansicht** (ein Tag pro Seite) und **kompakter Wochenansicht** (Tage nebeneinander als Spalten). Eine **Uhrzeit-Linie** markiert die aktuelle Zeit; Stunden, in denen du (mindestens zur Hälfte) abwesend warst, werden **ausgegraut** dargestellt. Tippen auf eine Stunde öffnet die Detailansicht (Zeit, Lehrer, Raum, Klassen, Info, Inhalt, Notizen). Ferien-Hinweis, wenn in den nächsten Tagen kein Unterricht ansteht; Button „Zur aktuellen Woche“. |
| **Unterrichtsinhalte** | Eingetragene Unterrichtsinhalte der letzten Tage, **gruppierbar nach Tag oder Fach**. Standard-Zeitraum in den Einstellungen wählbar; „Weitere Tage laden“ erweitert den Zeitraum schrittweise (+14 Tage, maximal 180 Tage). |
| **Hausaufgaben** | Tabs **Aktuell** und **Vergangen**, Abhak-Funktion, Fälligkeits-Ampel, Fachfarben und Anhang-Download. |
| **Nachrichten** | Tabs **Posteingang**, **Gesendet** und **Entwürfe**. Nachrichten **verfassen**, **beantworten**, als Entwurf speichern, **löschen** und als **ungelesen markieren**; Empfängersuche, Anhang-Download, Nachrichtenverlauf und Account-Kennzeichnung. |
| **Abwesenheiten** | Fehlzeiten des aktuellen Schuljahres mit Entschuldigungs-Status, **Filter nach Status** und Zähler unentschuldigter Fehlzeiten. Umschaltbar zwischen **Nachrichten-** und **Listenansicht**; mehrtägige Abwesenheiten werden zusammengefasst (verpasste Tage/Stunden/Minuten, „zählt/zählt nicht“). Abwesenheiten können **gemeldet, bearbeitet und gelöscht** werden (sofern WebUntis das für den Account erlaubt). |
| **Termine** | Prüfungen und Schulereignisse – Tabs **Anstehend** (nächste 90 Tage) und **Vergangen** (ab Beginn des Schuljahres). |
| **Klassenbuch** | Einträge des gesamten Schuljahres (Hausaufgaben vergessen, Abwesenheit, Verspätung, Bemerkung usw.) mit Typ-Kategorisierung. |
| **Einstellungen** | Haupt-Account (Anzeigename), weitere Kinder, Stundenplan (Anzahl Tage, Ansicht), Anzeige (Lang-/Kurznamen für Fächer, Lehrer und Räume, 2. Zeile der Wochenansicht), Zeitraum der Unterrichtsinhalte, Cache-Dauer, Benachrichtigungen (inkl. Akku-/Autostart-Hilfen), Export/Import, App-Updates und Abmelden. |

### Benachrichtigungen

Optionale Funktion (in den Einstellungen aktivierbar), die per periodischem **WorkManager**-Job (alle 15 Minuten, nur bei bestehender Netzwerkverbindung) im Hintergrund auf Änderungen prüft und lokal benachrichtigt bei:

- Ausfällen, Vertretungen und Raumänderungen im Stundenplan der nächsten Tage
- neuen Nachrichten im Posteingang
- neuen Hausaufgaben
- neuen Klassenbucheinträgen

Jede Kategorie hat einen eigenen Benachrichtigungskanal (individuell stumm-/einstellbar über die Systemeinstellungen). Der erste Lauf nach dem Aktivieren legt nur einen Ausgangszustand an, ohne Benachrichtigungsflut für bereits bestehende Einträge; ab Android 13 wird zusätzlich die Laufzeit-Berechtigung `POST_NOTIFICATIONS` benötigt.

Zusätzlich in den Einstellungen:

- **„Jetzt auf Änderungen prüfen“** startet den Check sofort zum Testen.
- **„Was ist neu“** zeigt die Änderungen der letzten 7 Tage (auch per Tipp auf eine Benachrichtigung erreichbar); neue Einträge sind markiert, der Verlauf kann gelöscht werden.
- **Akku-Optimierung deaktivieren** und **Autostart aktivieren** (herstellerabhängig, z. B. Samsung, Xiaomi, Huawei, Oppo, Vivo): Viele Hersteller beenden Hintergrund-Jobs sonst vorzeitig.

### Features

- ✅ **Material 3 Design:** Volle Unterstützung für Light + Dark Mode.
- ✅ **Zweisprachig:** Vollständige Lokalisierung auf Deutsch und Englisch.
- ✅ **Sicherheit:** AES-256 verschlüsselte Speicherung der Zugangsdaten; im Release-Build werden nur System-Zertifikate akzeptiert.
- ✅ **Mehrere Kinder:** Beliebig viele zusätzliche Accounts, Kinder-Umschalter, gemeinsamer Nachrichten-Posteingang.
- ✅ **Intelligenter Stundenplan:** Eigener Plan, Klassenplan und kombinierter Plan; Tages- oder kompakte Wochenansicht; Vertretungsvisualisierung, Status-Badges, aktuelle Uhrzeit-Linie, Abwesenheits-Overlay.
- ✅ **Kombinierter Stundenplan:** Frei wählbare Klassenfächer (mit ausgeschriebenem Namen + Kürzel) werden nur in freie Stunden des persönlichen Plans eingeblendet.
- ✅ **Unterrichtsinhalte:** Eigener Bereich mit Gruppierung nach Tag/Fach und nachladbarem Zeitraum.
- ✅ **Hausaufgaben:** Aktuelle/vergangene Aufgaben, Abhak-Status, Anhang-Download.
- ✅ **Nachrichten:** Verfassen, Antworten, Entwürfe, Gesendet, Löschen, „Als ungelesen markieren“, Anhang-Download (inkl. S3-URL-Handling).
- ✅ **Abwesenheiten:** Filter, zwei Ansichten, Abwesenheiten melden/bearbeiten/löschen.
- ✅ **Termine:** Anstehende und vergangene Termine in getrennten Tabs.
- ✅ **Anzeige-Optionen:** Lang- oder Kurznamen für Fächer, Lehrer und Räume, optional mit Kürzel in Klammern.
- ✅ **Einstellungen sichern:** Export und Import der Konfiguration (inkl. Accounts) als JSON-Datei.
- ✅ **Performance & Offline-Anzeige:** In-Memory-Cache mit konfigurierbarer TTL (0–60 Minuten, Standard 5) plus **Festplatten-Cache** (Stale-while-revalidate): Zuletzt bekannte Daten erscheinen sofort, während im Hintergrund still aktualisiert wird. Pull-to-Refresh lädt immer neu.
- ✅ **Robustheit:** Automatischer Silent-Re-Login bei abgelaufenen Sessions.
- ✅ **Auto-Update:** In-App Update-Check und Installation per GitHub Releases.
- ✅ **Hintergrund-Benachrichtigungen:** Periodischer Änderungs-Check (Stundenplan, Nachrichten, Hausaufgaben, Klassenbuch) via WorkManager mit eigenen Kanälen pro Kategorie und „Was ist neu“-Verlauf.

### Architektur

```
app/
├── api/
│   ├── WebUntisService.kt        # Retrofit Interface (JSON-RPC, REST v1/v2, S3-Download)
│   ├── WebUntisRepository.kt     # Zentrale Datenlogik, Caching & Multi-Account-Merging
│   ├── DiskCache.kt              # Persistenter Cache der zuletzt bekannten Daten
│   ├── SessionManager.kt         # Verschlüsselte Session, Präferenzen, Export/Import
│   ├── ActiveAccountManager.kt   # Aktives Kind / aktiver Account
│   ├── AppForegroundEvents.kt    # Signal „App kommt in den Vordergrund“ (stilles Neuladen)
│   ├── RetrofitFactory.kt        # Dynamische Base-URL & Interceptor-Setup
│   ├── NetworkModule.kt          # Hilt DI, Cookie-Handling (Android 15 Fix)
│   ├── NotificationScheduler.kt  # Verwaltet den periodischen WorkManager-Job
│   ├── PlanChangeCheckWorker.kt  # Hilt-Worker: prüft im Hintergrund auf Änderungen
│   ├── ChangeSnapshot.kt         # Zustand des letzten Checks & „Was ist neu“-Verlauf
│   ├── NotificationHelper.kt     # Erstellt Kanäle & postet lokale Benachrichtigungen
│   ├── UpdateManager.kt          # Update-Check, Download & Installation
│   └── GithubService.kt          # GitHub-Releases-API
├── model/
│   ├── Models.kt                 # GSON-kompatible Datenklassen für alle API-Versionen
│   └── GithubRelease.kt
├── util/
│   └── FileTypeUtils.kt
└── ui/
    ├── login/                    # Login-Flow & Validierung
    ├── common/                   # Kinder-Umschalter
    ├── timetable/                # Stundenplan (Tag: ViewPager2 · Woche: Grid), Uhrzeit-Linie, Abwesenheits-Overlay
    ├── classbook/                # Klassenbuch + Unterrichtsinhalte
    ├── homework/                 # Hausaufgaben inkl. Datei-Handling
    ├── messages/                 # Nachrichten (Posteingang/Gesendet/Entwürfe), Anhänge & Verlauf
    ├── absences/                 # Abwesenheiten: Filter, Ansichten, Melden/Bearbeiten/Löschen
    ├── events/                   # Termine & Prüfungen (anstehend/vergangen)
    ├── changes/                  # „Was ist neu“-Dialog
    └── settings/                 # Accounts, Anzeige, Benachrichtigungen, Export/Import, Updates
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding · DataStore · WorkManager (Hilt-Work)

### Bekannte Einschränkungen

- Der Hausaufgaben-Abhakstatus ist nicht persistent (wird bei App-Neustart zurückgesetzt).
- Die WebUntis-API ist inoffiziell; serverseitige Änderungen können Funktionen beeinträchtigen.
- Welche Bereiche (z. B. Klassenbuch, Abwesenheiten melden/bearbeiten, Nachrichten senden) nutzbar sind, hängt von der Konfiguration der Schule und vom Account-Typ ab.
- Der Hintergrund-Check läuft periodisch alle 15 Minuten (Minimum von WorkManager); das Betriebssystem (Doze/Akku-Optimierung) kann die tatsächliche Ausführung verzögern.

### Kompatibilität

Optimiert für moderne Android-Versionen (Ziel-SDK 36, minSdk 26). Enthält spezifische Fixes für das Cookie-Handling unter Android 15 (`Secure`-Flag Problem).

### Build

```bash
./gradlew assembleDebug
```

Die Versionsnummer wird in `dependencies.gradle` gepflegt (aktuell `0.5.4`).

---

<a name="english"></a>
## English

Native Kotlin Android app for WebUntis students and parents with timetable, lesson content, homework, messages, absences, events and class register – including support for multiple children and background notifications.

### Requirements

| Tool | Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ (the GitHub workflow builds with JDK 21) |
| Android SDK | API 26+ (minSdk) / API 36 (compileSdk / targetSdk) |
| Kotlin | 2.1.20 |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 9.4.1 (via the Gradle wrapper) |

### Opening the project

1. Clone the repository or extract the ZIP
2. Android Studio → **Open** → select project folder
3. Wait for Gradle sync
4. Select device / emulator → **▶ Run**

### Sign-in

| Field | Example |
|---|---|
| Server | `my-school.webuntis.com` |
| School short name | found in the WebUntis URL after `/WebUntis/` |
| Username | your WebUntis login |
| Password | your WebUntis password |

> **Note:** If your school uses SSO (Microsoft, Google, school portal), you still need separate direct WebUntis credentials, set up via the WebUntis website.

Credentials are stored locally with **EncryptedSharedPreferences** (AES-256) and never leave the device (other than to sign in to your own WebUntis server). For parent accounts with several children, a child selection appears after sign-in.

#### Multiple children / accounts

In Settings you can add any number of **additional children** (each with their own login and a freely chosen display name) – e.g. as a parent alongside the main account. The main account can have a display name too. Use the **child switcher** in the title bar to change between profiles; timetable, homework, absences etc. then show the selected child's data. Messages from all accounts are merged into a single inbox (with account labels).

### Navigation

Scrollable bottom navigation bar with every section directly reachable: **Timetable**, **Lesson content**, **Homework**, **Messages**, **Absences**, **Events**, **Class register** and **Settings**.

### Screens & Features

| Screen | Description |
|---|---|
| **Timetable** | Configurable number of school days (1–20) with substitution info ("instead of …"), lesson content, pinned teacher notes and colour-coded status badges (cancelled, substitution, extra, exam). Switchable between **personal plan**, **class timetable** and **combined timetable** (your personal plan with freely selectable class subjects filled into free periods), and between a **day view** (one day per page) and a **compact week view** (days side by side as columns). A **current-time line** marks the present moment; lessons you were absent for (at least half of their duration) are **greyed out**. Tapping a lesson opens a detail view (time, teacher, room, classes, info, content, notes). A holiday notice appears when no lessons are scheduled for the coming days; a "To current week" button jumps back. |
| **Lesson content** | Recorded lesson content for the last days, **groupable by day or subject**. The default period is configurable in Settings; "Load more days" widens it step by step (+14 days, up to 180 days). |
| **Homework** | **Current** and **Past** tabs, check-off function, due-date traffic light, subject colours and attachment download. |
| **Messages** | **Inbox**, **Sent** and **Drafts** tabs. **Compose**, **reply**, save as draft, **delete** and **mark as unread**; recipient search, attachment download, message history and account labelling. |
| **Absences** | Absences for the current school year with excuse status, **filter by status** and a counter of unexcused absences. Switchable between a **messages view** and a **list view**; multi-day absences are merged (missed days/periods/minutes, "counts / doesn't count"). Absences can be **reported, edited and deleted** (where WebUntis allows it for the account). |
| **Events** | Exams and school events – **Upcoming** (next 90 days) and **Past** (from the start of the school year) tabs. |
| **Class register** | Entries for the whole school year (forgotten homework, absence, late, remark, etc.) with type categorisation. |
| **Settings** | Main account (display name), additional children, timetable (day count, view), display (long/short names for subjects, teachers and rooms, 2nd line in week view), lesson content period, cache duration, notifications (incl. battery / autostart helpers), export/import, app updates and sign-out. |

### Notifications

Optional feature (enabled in Settings) that runs a periodic **WorkManager** job (every 15 minutes, network required) in the background to check for changes and post local notifications for:

- cancellations, substitutions and room changes in the upcoming timetable
- new messages in the inbox
- new homework
- new class register entries

Each category has its own notification channel (individually mutable/configurable via system settings). The first run after enabling only establishes a baseline, so it won't flood you with notifications for things that already existed; on Android 13+ the runtime `POST_NOTIFICATIONS` permission is also required.

Also in Settings:

- **"Check for changes now"** runs the check immediately for testing.
- **"Show what's new"** lists the changes of the last 7 days (also reachable by tapping a notification); new entries are marked and the history can be cleared.
- **Disable battery optimisation** and **enable autostart** (vendor-dependent, e.g. Samsung, Xiaomi, Huawei, Oppo, Vivo): many vendors otherwise kill background jobs early.

### Features

- ✅ **Material 3 Design:** Full Light + Dark mode support.
- ✅ **Bilingual:** Full localisation in German and English.
- ✅ **Security:** AES-256 encrypted storage of credentials; release builds only trust system certificates.
- ✅ **Multiple children:** Any number of additional accounts, child switcher, shared message inbox.
- ✅ **Smart timetable:** Personal, class and combined plan; day or compact week view; substitution visualisation, status badges, current-time line, absence overlay.
- ✅ **Combined timetable:** Freely selectable class subjects (shown with their full name + abbreviation) are filled only into free periods of your personal plan.
- ✅ **Lesson content:** Dedicated section with day/subject grouping and an extendable period.
- ✅ **Homework:** Current/past tasks, check-off state, attachment download.
- ✅ **Messages:** Compose, reply, drafts, sent, delete, "mark as unread", attachment download (incl. S3 URL handling), history view.
- ✅ **Absences:** Filter, two views, report/edit/delete absences.
- ✅ **Events:** Upcoming and past events in separate tabs.
- ✅ **Display options:** Long or short names for subjects, teachers and rooms, optionally with the abbreviation in parentheses.
- ✅ **Settings backup:** Export and import of the configuration (incl. accounts) as a JSON file.
- ✅ **Performance & offline display:** In-memory cache with configurable TTL (0–60 minutes, default 5) plus a **disk cache** (stale-while-revalidate): last known data appears instantly while fresh data loads silently in the background. Pull-to-refresh always reloads.
- ✅ **Resilience:** Automatic silent re-login on expired sessions.
- ✅ **Auto-update:** In-app update check and installation via GitHub Releases.
- ✅ **Background notifications:** Periodic change check (timetable, messages, homework, class register) via WorkManager, with its own channel per category and a "What's new" history.

### Architecture

```
app/
├── api/
│   ├── WebUntisService.kt        # Retrofit interface (JSON-RPC, REST v1/v2, S3 download)
│   ├── WebUntisRepository.kt     # Central data logic, caching & multi-account merging
│   ├── DiskCache.kt              # Persistent cache of the last known data
│   ├── SessionManager.kt         # Encrypted session, preferences, export/import
│   ├── ActiveAccountManager.kt   # Active child / account
│   ├── AppForegroundEvents.kt    # "App returns to foreground" signal (silent reload)
│   ├── RetrofitFactory.kt        # Dynamic base URL & interceptor setup
│   ├── NetworkModule.kt          # Hilt DI, cookie handling (Android 15 fix)
│   ├── NotificationScheduler.kt  # Manages the periodic WorkManager job
│   ├── PlanChangeCheckWorker.kt  # Hilt worker: checks for changes in the background
│   ├── ChangeSnapshot.kt         # State of the last check & "What's new" history
│   ├── NotificationHelper.kt     # Creates channels & posts local notifications
│   ├── UpdateManager.kt          # Update check, download & installation
│   └── GithubService.kt          # GitHub releases API
├── model/
│   ├── Models.kt                 # GSON-compatible data classes for all API versions
│   └── GithubRelease.kt
├── util/
│   └── FileTypeUtils.kt
└── ui/
    ├── login/                    # Login flow & validation
    ├── common/                   # Child switcher
    ├── timetable/                # Timetable (day: ViewPager2 · week: grid), time line, absence overlay
    ├── classbook/                # Class register + lesson content
    ├── homework/                 # Homework incl. file handling
    ├── messages/                 # Messages (inbox/sent/drafts), attachments & history
    ├── absences/                 # Absences: filter, views, report/edit/delete
    ├── events/                   # Events & exams (upcoming/past)
    ├── changes/                  # "What's new" dialog
    └── settings/                 # Accounts, display, notifications, export/import, updates
```

**Stack:** MVVM · Hilt DI · Retrofit2 · OkHttp3 · Coroutines/Flow · Navigation Component · Material 3 · ViewBinding · DataStore · WorkManager (Hilt-Work)

### Known limitations

- The homework check-off state is not persistent (resets on app restart).
- The WebUntis API is unofficial; server-side changes may affect functionality.
- Which sections (e.g. class register, reporting/editing absences, sending messages) are usable depends on the school's configuration and the account type.
- The background check runs periodically every 15 minutes (WorkManager's minimum); the OS (Doze/battery optimisation) may delay actual execution.

### Compatibility

Optimised for modern Android versions (target SDK 36, minSdk 26). Includes specific fixes for cookie handling on Android 15 (`Secure` flag issue).

### Build

```bash
./gradlew assembleDebug
```

The version number is maintained in `dependencies.gradle` (currently `0.5.4`).
