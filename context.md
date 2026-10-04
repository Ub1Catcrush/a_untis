# Project Context: WebUntis Dashboard

## Overview
Native Android application (Kotlin) for student/parent WebUntis dashboards.

## General Rules (MANDATORY)
1. **Localization:** ALL user-facing strings MUST be in `app/src/main/res/values/strings.xml`. Hardcoded strings in code or layouts are strictly prohibited.
2. **Context Management:** This file (`context.md`) must be updated whenever new global rules, major architectural shifts, or important technical insights are identified.

## Architecture
- **Pattern:** MVVM + Hilt DI.
- **Networking:** Retrofit2/OkHttp (JSON-RPC, REST v1/v2). Android 15 requires strict `Secure` cookie handling and modern User-Agents.
- **UI:** Material 3, ViewBinding, Navigation Component.
- **State:** `UiState` wrapper (Loading, Success, Error) with Kotlin Flow.

## Feature: Timetable
- **Classic View:** ViewPager2 (one day per page).
- **Compact View:** Horizontal RecyclerView showing days as columns.
- **Overlaps:** Overlapping lessons are merged. If an active lesson replaces a cancelled one, it is marked as a substitution and displays "statt [Old Subject]".
- **Interactions:** Tapping a lesson (especially in Compact View) opens a `MaterialAlertDialog` with details (Subject, Time, Room, Teacher, Teaching Content, Notes).

## Caching / Stale-while-revalidate (MANDATORY pattern for data screens)
- `WebUntisRepository` keeps in-memory caches (TTL from settings) AND mirrors every successful result to disk via `DiskCache` (`noBackupFilesDir/data_cache`, keys scoped by server+user+active account, files tagged with app versionCode so other versions are ignored).
- Each category has a `peekX()` that returns the last known data (memory, else disk) WITHOUT network. ViewModels must: (1) if no `Success` yet, show `peekX()`; (2) only emit `Loading` if nothing is displayed; (3) refresh via `getX()` silently; (4) on failure keep stale content and only emit `Error` when nothing is displayed.
- `load(..., contextChanged = true)` is used when the data belongs to something else (account switch, timetable mode, past/upcoming tab). The old content is then replaced by the cached data of the NEW context (`peekX()`), and only if none exists a spinner is shown. A running load for the old context is cancelled (`loadJob`). Fields used by `load()` must be declared ABOVE the `init {}` block (viewModelScope runs immediately).
- Pull-to-refresh passes `userInitiated = true`; the swipe spinner is bound to the ViewModel's `refreshing` flow. Automatic reloads (start, app resume) are invisible.
- `clearDataCachesOnly()` / logout also wipe the disk cache. Mutations (send/delete message, save draft) delete the affected disk entries.
- Unterrichtsinhalte (`getTeachingContentEntries`) are cached per account too (only lessons with content + covered days). Once older than the cache TTL only the last 7 days are re-fetched and merged; pull-to-refresh reloads the whole window.
- Not cached on disk: per-message detail/attachments.

## Key Components
- `WebUntisRepository`: Singleton, handles parallel fetching, caching, and logical merging of lessons.
- `SessionManager`: Encrypted storage for credentials and plain storage for UI preferences.
- `NetworkModule`: Configures OkHttpClient with a custom `CookieJar` and `jsonSanitizer` to handle WebUntis session expiry (HTML-to-JSON conversion).

## Current Status
- **Version:** v0.0.12 (defined in `dependencies.gradle`).
- **Target SDK:** 35 (Android 15).

## Notifications (PlanChangeCheckWorker / NotificationHelper)
- Six independent categories (`NotificationCategory`): cancellations, substitutions (incl. subject change), room changes, messages, homework, classbook. Each is its OWN Android notification channel (timetable ones grouped) AND has its own switch in Settings (`SessionManager.isNotificationCategoryEnabled`, default on, part of settings export/import). Master switch `notificationsEnabled` still gates the whole worker.
- A disabled category never notifies or logs to "Neuigkeiten", but the worker still advances its baseline so re-enabling doesn't replay old items.
- Summary notification (>4 items) is decided per category/channel, never across categories.
- Old channel `channel_timetable_changes` is deleted in `ensureChannels()`.

## Absences screen
- "Nachrichten" view: every absence is shown individually (`toSingleClusters`). Merging consecutive days/ranges happens ONLY in the "Liste" view (`groupIntoAbsenceEntries`).

## Login: school search
- Login screen has a search field (name/town) backed by WebUntis' public `POST https://mobile.webuntis.com/ms/schoolquery2` (JSON-RPC `searchSchool`, see `SchoolSearchService`). Selecting a hit fills server (`School.serverHost`) and school short name (`loginName`); manual fields stay editable as fallback. Debounced (400 ms, min 3 chars) in `LoginViewModel.searchSchools`.

## Klar-/Kurznamen (per screen)
- `NameScreen` × `NameType` → `NameStyle(long, shortInParens)`, stored by `SessionManager.nameStyle/setNameStyle` (prefs `name_style_<SCREEN>_<TYPE>_long|_parens`, part of settings export/import; legacy global backup keys map to DAY_VIEW).
- Screens: day view (subject/teacher/room), week view (subject on 1st tile line), lesson content, classbook, homework, messages (sender/recipient), events. Absences show no subject/teacher/room names, so there is no setting for them. Add a screen by extending `NameScreen` (the settings UI is generated from it) and applying the style where the names are rendered.
- Defaults reproduce the pre-setting behaviour (day view keeps the old global switches; homework/messages/events = "Langname (Kürzel)"; lesson content subject long). `NameCatalog.subjectDisplay/teacherDisplay(raw, style)` handle screens that only get short codes.
- Week view: each tile line has a content choice + name style. First line: `SessionManager.weekViewFirstLine` (SUBJECT/TEACHER/ROOM, default SUBJECT) styled via `NameScreen.WEEK_VIEW` + type. Second line: `weekViewSecondLine` (SUBJECT/TEACHER/ROOM/NONE; legacy *_LONG_NAME values still parsed) styled via `NameScreen.WEEK_VIEW_LINE2` + type. Enum is `SessionManager.WeekViewLine`. Both are configured inside the week-view block of the name settings; tile colour still comes from the lesson's subject.
- `useCompactWeekView` is only the Tag/Woche toggle state of the timetable toolbar; there is intentionally no settings switch for it (and no CompactWeekAdapter any more).
