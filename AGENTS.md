# AGENTS Guide - SofiaProduction

## Big Picture (read this first)
- Single-module Android app (`:app`) using Kotlin + XML views + ViewBinding (no Compose).
- Main runtime flow: `MainActivity` hosts `DashboardFragment` and `SettingsFragment` (`app/src/main/java/com/lemarc/sofiaproduction/MainActivity.kt`).
- Data boundary is `SofiaRepository` (`app/src/main/java/com/lemarc/sofiaproduction/data/SofiaRepository.kt`), which wraps Retrofit calls to `https://energy-api.robinhawkes.com/`.
- The same repository powers both UI and home-screen widget updates (`app/src/main/java/com/lemarc/sofiaproduction/widget/PowerWidget.kt`).
- App settings (BMU IDs, test mode) are global via `AppSettings` SharedPreferences singleton; initialized in `SofiaApplication`.

## Architecture and Data Flow
- `DashboardViewModel` polls every 30 min and exposes `UiState` + `refreshing` + `chartDays` as `StateFlow` (`ui/dashboard/DashboardViewModel.kt`).
- `fetchSnapshot()` composes generation + notifications into `FarmSnapshot`; generation merges multiple BMUs/time slots and tolerates partial day failures.
- UI state policy: keep last successful data on transient failures; only show error screen when no prior success (`DashboardViewModel.load`).
- Chart window is user-controlled (1-14 days) via slider in `fragment_dashboard.xml`; changing it triggers reload.
- Widget lifecycle: `SofiaWidgetProvider` schedules a unique periodic WorkManager job (`sofia_widget_refresh`) and also triggers immediate one-time refresh.

## Build/Test/Run Workflows (Windows)
- Debug APK: `./gradlew.bat :app:assembleDebug`
- Unit tests: `./gradlew.bat :app:testDebugUnitTest`
- Instrumented tests (device/emulator required): `./gradlew.bat :app:connectedDebugAndroidTest`
- Lint: `./gradlew.bat :app:lintDebug`
- Release APK: `./gradlew.bat :app:assembleRelease` (requires `keystore.properties` with `storeFile/storePassword/keyAlias/keyPassword`).

## Project-Specific Conventions
- BMU IDs are comma-separated text in settings UI, persisted as comma-separated string (`AppSettings.setBmuIds`).
- "Test mode" is inferred (not toggled): active when configured BMU set differs from defaults; banner visibility comes from `MainActivity.updateTestBanner()`.
- Time handling is UTC ISO strings; code frequently normalizes API values with `replace(' ', 'T')` before `Instant.parse(...)`.
- Status semantics are domain-specific (`FarmSnapshot.statusLabel`): Unplanned outage > Planned maintenance > Generating > Standby.
- Source badge convention: `b1610` = metered, otherwise treat as forecast (`DashboardFragment` and widget rendering).

## Integrations and External Dependencies
- Networking: Retrofit + OkHttp + Gson (`app/build.gradle.kts`), logging interceptor set to `BASIC`.
- Charting: MPAndroidChart for historical generation curve and marker view (`ui/dashboard/ChartMarkerView.kt`).
- Background refresh: WorkManager periodic 30 min cadence plus on-demand one-time work.
- Widget provider is declared in manifest as `.widget.SofiaWidgetProvider` while implemented in `PowerWidget.kt`.
- Domain/API rationale and payload examples are documented in `sofia_api_summary.md` (useful when adjusting parsing/aggregation logic).

## Safe Change Checklist for Agents
- If you change data models or parsing, verify both dashboard and widget paths still render (`SofiaRepository` is shared).
- If you change BMU settings behavior, re-check test banner logic and reset/save flows in `SettingsFragment` + `MainActivity`.
- If you change polling cadence, update both `DashboardViewModel` and widget WorkManager scheduling to avoid drift.
- Keep installed capacity constant (`INSTALLED_MW = 1400.0`) aligned with capacity-factor calculations and widget percent ring.

