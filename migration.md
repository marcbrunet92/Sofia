# Migration Guide — Android → iOS

This document explains how to build an iOS equivalent of the Sofia Wind Farm app, mapping every Android concept to its iOS counterpart.

---

## 1. Tech-stack mapping

| Android                         | iOS equivalent                                   |
|---------------------------------|--------------------------------------------------|
| Kotlin                          | Swift                                            |
| XML layouts + ViewBinding       | SwiftUI (declarative, no XML)                    |
| ViewModel + StateFlow           | `@Observable` class or `ObservableObject` + `@Published` |
| Retrofit + OkHttp + Gson        | `URLSession` (built-in) or Alamofire + Codable   |
| SharedPreferences               | `UserDefaults`                                   |
| WorkManager (periodic tasks)    | `BGTaskScheduler` / `BGAppRefreshTask`           |
| Android App Widget              | WidgetKit (`Widget` + `TimelineProvider`)        |
| Gradle + `build.gradle.kts`     | Xcode + `Package.swift` (Swift Package Manager) |
| `Application` subclass          | `AppDelegate` / `@main App` struct               |
| Coroutines (`suspend`)          | Swift Concurrency (`async/await`)                |
| MPAndroidChart                  | Swift Charts (built-in, iOS 16+)                 |

---

## 2. Project setup

1. Open **Xcode** → *New Project* → **App** template.
2. Set *Interface* to **SwiftUI**, *Language* to **Swift**.
3. Enable the **Widget Extension** target: *File → New → Target → Widget Extension*.
4. Add Alamofire via Swift Package Manager if you prefer it over raw `URLSession`:  
   `https://github.com/Alamofire/Alamofire` (optional — `URLSession` is sufficient).

---

## 3. App entry point

Android uses a subclassed `Application` to initialise `AppSettings`.  
iOS uses the `@main App` struct:

```swift
// SofiaApp.swift
@main
struct SofiaApp: App {
    init() {
        AppSettings.shared.migrate()   // equivalent of AppSettings.init(context)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

---

## 4. Settings (`AppSettings` → `UserDefaults`)

```swift
// AppSettings.swift
final class AppSettings {
    static let shared = AppSettings()

    private let defaults = UserDefaults.standard   // or an App Group suite for widget sharing

    static let defaultBmuIds = ["SOFWO-11", "SOFWO-12", "SOFWO-21", "SOFWO-22"]

    var bmuIds: [String] {
        get {
            let stored = defaults.string(forKey: "bmu_ids") ?? ""
            let parsed = stored.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            return parsed.isEmpty ? Self.defaultBmuIds : parsed
        }
        set {
            defaults.set(newValue.joined(separator: ","), forKey: "bmu_ids")
        }
    }

    var isTestMode: Bool {
        Set(bmuIds) != Set(Self.defaultBmuIds)
    }

    // API mode: "legacy" | "sofia"
    var apiMode: String {
        get { defaults.string(forKey: "api_mode") ?? "legacy" }
        set { defaults.set(newValue, forKey: "api_mode") }
    }

    // Snapshot cache (JSON string)
    var snapshotJson: String? {
        get { defaults.string(forKey: "snapshot_cache") }
        set { defaults.set(newValue, forKey: "snapshot_cache") }
    }
}
```

> **Widget data sharing**: use an **App Group** (`UserDefaults(suiteName: "group.com.yourapp.sofia")`) so both the main app and the widget extension read/write the same `UserDefaults`.

---

## 5. Networking & data models

### 5.1 API models (Codable replaces Gson)

```swift
// Models.swift

struct GenerationSlotRaw: Codable {
    let timeFrom: String
    let timeTo: String
    let levelTo: Double?
    let capacityMW: Double?
    let source: String?

    enum CodingKeys: String, CodingKey {
        case timeFrom, timeTo, levelTo, capacityMW, source
    }
}

struct GenerationPoint: Codable {
    let timeFrom: String
    let totalMW: Double
    let source: String

    var capacityFactor: Double { totalMW / 1_400.0 }
}

struct ActiveNotice: Codable {
    let bmuId: String
    let documentId: String
    let timeFrom: String
    let timeTo: String
    let reasonCode: String
    let reasonDescription: String
    let unavailabilityType: String
    let levelMW: Double
}

struct FarmSnapshot: Codable {
    let latestMW: Double
    let capacityFactor: Double
    let source: String
    let lastUpdated: String
    let history: [GenerationPoint]
    let activeNotices: [ActiveNotice]

    var hasActiveOutage: Bool { !activeNotices.isEmpty }
    var statusLabel: String {
        if hasActiveOutage && activeNotices.contains(where: { $0.unavailabilityType == "Unplanned" }) {
            return "Unplanned outage"
        } else if hasActiveOutage {
            return "Planned maintenance"
        } else if latestMW > 0 {
            return "Generating"
        } else {
            return "Standby"
        }
    }
}

// Sofia API models
struct SofiaGenerationPoint: Codable {
    let timeFrom: String
    let timeTo: String
    let totalMW: Double
    let source: String

    enum CodingKeys: String, CodingKey {
        case timeFrom = "time_from"
        case timeTo   = "time_to"
        case totalMW  = "total_mw"
        case source
    }
}

struct DataRangeInfo: Codable {
    let latestDate: String?
    let oldestDate: String?
    let totalDays: Int

    enum CodingKeys: String, CodingKey {
        case latestDate = "latest_date"
        case oldestDate = "oldest_date"
        case totalDays  = "total_days"
    }
}
```

### 5.2 API clients

```swift
// EnergyApiClient.swift — mirrors SofiaRepository (Robinhawkes endpoint)

struct EnergyApiClient {
    private let baseURL = URL(string: "https://energy-api.robinhawkes.com")!
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    /// Fetch one day of generation history and aggregate across BMU IDs.
    func fetchGenerationDay(dateISO: String, bmuIds: [String]) async throws -> [GenerationPoint] {
        var components = URLComponents(url: baseURL.appendingPathComponent("generation-history"), resolvingAgainstBaseURL: false)!
        var items = [URLQueryItem(name: "date", value: dateISO)]
        items += bmuIds.map { URLQueryItem(name: "bmrsids", value: $0) }
        components.queryItems = items

        let (data, _) = try await URLSession.shared.data(from: components.url!)
        let body = try JSONSerialization.jsonObject(with: data) as! [String: Any]

        var totals: [String: (Double, String)] = [:]
        for bmuId in bmuIds {
            guard let bmuObj = body[bmuId] as? [String: Any],
                  let generation = bmuObj["generation"] as? [[String: Any]] else { continue }
            for slot in generation {
                let t = slot["timeFrom"] as? String ?? ""
                let level = (slot["levelTo"] as? Double) ?? 0.0
                let source = (slot["source"] as? String) ?? "pn"
                let prev = totals[t]
                totals[t] = ((prev?.0 ?? 0) + level, source)
            }
        }

        return totals
            .map { GenerationPoint(timeFrom: $0.key, totalMW: $0.value.0, source: $0.value.1) }
            .sorted { $0.timeFrom < $1.timeFrom }
    }
}
```

```swift
// SofiaApiClient.swift — mirrors SofiaApiClient (sofia.lemarc.fr endpoint)

struct SofiaApiClient {
    private let baseURL = URL(string: "https://sofia.lemarc.fr")!
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    func fetchGeneration(from: String, to: String, bmuIds: [String]) async throws -> [SofiaGenerationPoint] {
        var components = URLComponents(url: baseURL.appendingPathComponent("generation"), resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "from_date", value: from),
            URLQueryItem(name: "to_date",   value: to),
        ]
        items += bmuIds.map { URLQueryItem(name: "bmu_ids", value: $0) }
        components.queryItems = items

        let (data, _) = try await URLSession.shared.data(from: components.url!)
        return try decoder.decode([SofiaGenerationPoint].self, from: data)
    }

    func fetchLatestDate() async throws -> DataRangeInfo {
        let url = baseURL.appendingPathComponent("latest-date")
        let (data, _) = try await URLSession.shared.data(from: url)
        return try decoder.decode(DataRangeInfo.self, from: data)
    }
}
```

---

## 6. Repository

```swift
// SofiaRepository.swift

final class SofiaRepository {
    private let energyApi = EnergyApiClient()
    private let sofiaApi  = SofiaApiClient()
    private let settings  = AppSettings.shared

    // ── Generation (legacy Robinhawkes API) ──────

    func fetchGeneration(days: Int = 2) async throws -> [GenerationPoint] {
        let now = Date()
        let bmuIds = settings.bmuIds
        var allPoints: [String: (Double, String)] = [:]
        var successCount = 0

        for dayOffset in 0 ..< days {
            let target = Calendar.current.date(byAdding: .day, value: -dayOffset, to: now)!
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let dateISO = iso.string(from: target)

            do {
                let points = try await energyApi.fetchGenerationDay(dateISO: dateISO, bmuIds: bmuIds)
                for p in points {
                    let prev = allPoints[p.timeFrom]
                    allPoints[p.timeFrom] = ((prev?.0 ?? 0) + p.totalMW, p.source)
                }
                successCount += 1
            } catch {
                // Skip this day but continue
            }
        }

        guard successCount > 0 else { throw URLError(.cannotConnectToHost) }

        return allPoints
            .map { GenerationPoint(timeFrom: $0.key, totalMW: $0.value.0, source: $0.value.1) }
            .sorted { $0.timeFrom < $1.timeFrom }
    }

    // ── Snapshot (Sofia API) ─────────────────────

    func fetchSnapshotFromSofiaApi(days: Int) async throws -> FarmSnapshot {
        let now = Date()
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let fromDate = iso.string(from: Calendar.current.date(byAdding: .day, value: -days, to: now)!)
        let toDate   = iso.string(from: now)

        let sofiaPoints = try await sofiaApi.fetchGeneration(from: fromDate, to: toDate, bmuIds: settings.bmuIds)
        let genPoints = sofiaPoints.map { GenerationPoint(timeFrom: $0.timeFrom, totalMW: $0.totalMW, source: $0.source) }
        let latest = genPoints.last

        return FarmSnapshot(
            latestMW:      latest?.totalMW      ?? 0.0,
            capacityFactor: latest?.capacityFactor ?? 0.0,
            source:        latest?.source        ?? "pn",
            lastUpdated:   latest?.timeFrom      ?? "",
            history:       genPoints,
            activeNotices: []
        )
    }

    // ── Cache ────────────────────────────────────

    func cacheSnapshot(_ snapshot: FarmSnapshot) {
        let data = try? JSONEncoder().encode(snapshot)
        settings.snapshotJson = data.flatMap { String(data: $0, encoding: .utf8) }
    }

    func cachedSnapshot() -> FarmSnapshot? {
        guard let json = settings.snapshotJson,
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(FarmSnapshot.self, from: data)
    }
}
```

---

## 7. ViewModel

```swift
// DashboardViewModel.swift

@MainActor
@Observable
final class DashboardViewModel {
    enum UiState {
        case loading
        case success(FarmSnapshot)
        case error(String)
    }

    var uiState: UiState = .loading
    var refreshing = false
    var chartDays  = 2
    var maxChartDays = 14

    private let repo = SofiaRepository()
    private var pollTask: Task<Void, Never>?

    init() {
        if let cached = repo.cachedSnapshot() {
            uiState = .success(cached)
        }
        startPolling()
    }

    deinit { pollTask?.cancel() }

    func refresh() async {
        refreshing = true
        await load()
        refreshing = false
    }

    private func startPolling() {
        pollTask = Task {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(30 * 60))  // 30 min
            }
        }
    }

    private func load() async {
        let useSofiaApi = AppSettings.shared.apiMode == "sofia"
        do {
            let snapshot: FarmSnapshot
            if useSofiaApi {
                snapshot = try await repo.fetchSnapshotFromSofiaApi(days: 90)
            } else {
                let gen = try await repo.fetchGeneration(days: 14)
                snapshot = FarmSnapshot(
                    latestMW:      gen.last?.totalMW      ?? 0,
                    capacityFactor: gen.last?.capacityFactor ?? 0,
                    source:        gen.last?.source        ?? "pn",
                    lastUpdated:   gen.last?.timeFrom      ?? "",
                    history:       gen,
                    activeNotices: []
                )
            }
            uiState = .success(snapshot)
            repo.cacheSnapshot(snapshot)
        } catch {
            if case .success = uiState { return }   // keep last good data
            uiState = .error(error.localizedDescription)
        }
    }
}
```

---

## 8. Views (SwiftUI replaces XML + ViewBinding)

```swift
// DashboardView.swift

struct DashboardView: View {
    @State private var vm = DashboardViewModel()

    var body: some View {
        NavigationStack {
            switch vm.uiState {
            case .loading:
                ProgressView("Loading…")

            case .error(let msg):
                VStack {
                    Text("Error").font(.headline)
                    Text(msg).foregroundStyle(.secondary)
                    Button("Retry") { Task { await vm.refresh() } }
                }

            case .success(let snapshot):
                ScrollView {
                    VStack(spacing: 16) {
                        // Status banner
                        if snapshot.hasActiveOutage {
                            Text(snapshot.statusLabel)
                                .padding()
                                .background(snapshot.activeNotices.first?.unavailabilityType == "Unplanned"
                                            ? Color.red.opacity(0.15)
                                            : Color.orange.opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        }

                        // Current output
                        VStack {
                            Text("\(snapshot.latestMW, specifier: "%.0f") MW")
                                .font(.largeTitle.bold())
                            Text("\(snapshot.capacityFactor * 100, specifier: "%.1f")% capacity")
                                .foregroundStyle(.secondary)
                            Text(snapshot.source == "b1610" ? "Metered" : "Forecast")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }

                        // Chart (Swift Charts, iOS 16+)
                        GenerationChartView(
                            history: snapshot.history,
                            days: vm.chartDays
                        )

                        // Chart window slider
                        Slider(
                            value: Binding(
                                get: { Double(vm.chartDays) },
                                set: { vm.chartDays = Int($0) }
                            ),
                            in: 1...Double(vm.maxChartDays),
                            step: 1
                        )
                        .padding(.horizontal)
                    }
                    .padding()
                }
                .refreshable { await vm.refresh() }
            }
        }
        .navigationTitle("Sofia Wind Farm")
    }
}
```

### Chart view

```swift
import Charts

struct GenerationChartView: View {
    let history: [GenerationPoint]
    let days: Int

    private var visible: [GenerationPoint] {
        let cutoff = Calendar.current.date(byAdding: .day, value: -days, to: Date())!
        let cutoffISO = ISO8601DateFormatter().string(from: cutoff)
        return history.filter { $0.timeFrom >= cutoffISO }
    }

    var body: some View {
        Chart(visible) { point in
            LineMark(
                x: .value("Time", point.timeFrom),
                y: .value("MW",   point.totalMW)
            )
            .foregroundStyle(point.source == "b1610" ? .blue : .orange)
        }
        .frame(height: 200)
    }
}
```

---

## 9. Background refresh (WorkManager → BGTaskScheduler)

### Register the task (Info.plist)

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.yourapp.sofia.refresh</string>
</array>
```

### Schedule

```swift
// AppDelegate.swift or App init

import BackgroundTasks

func registerBackgroundTasks() {
    BGTaskScheduler.shared.register(
        forTaskWithIdentifier: "com.yourapp.sofia.refresh",
        using: nil
    ) { task in
        handleRefresh(task: task as! BGAppRefreshTask)
    }
}

func scheduleRefresh() {
    let request = BGAppRefreshTaskRequest(identifier: "com.yourapp.sofia.refresh")
    request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)  // 30 min
    try? BGTaskScheduler.shared.submit(request)
}

func handleRefresh(task: BGAppRefreshTask) {
    scheduleRefresh()  // reschedule for next time
    let refreshTask = Task {
        let repo = SofiaRepository()
        if let snapshot = try? await repo.fetchSnapshotFromSofiaApi(days: 90) {
            repo.cacheSnapshot(snapshot)
            WidgetCenter.shared.reloadAllTimelines()
        }
        task.setTaskCompleted(success: true)
    }
    task.expirationHandler = { refreshTask.cancel() }
}
```

---

## 10. Home screen widget (Android Widget → WidgetKit)

```swift
// SofiaWidget.swift

import WidgetKit
import SwiftUI

struct SofiaEntry: TimelineEntry {
    let date: Date
    let snapshot: FarmSnapshot?
}

struct SofiaProvider: TimelineProvider {
    func placeholder(in context: Context) -> SofiaEntry {
        SofiaEntry(date: .now, snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (SofiaEntry) -> Void) {
        let cached = SofiaRepository().cachedSnapshot()
        completion(SofiaEntry(date: .now, snapshot: cached))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SofiaEntry>) -> Void) {
        Task {
            let repo = SofiaRepository()
            let snapshot = try? await repo.fetchSnapshotFromSofiaApi(days: 1)
            if let snapshot { repo.cacheSnapshot(snapshot) }

            let entry = SofiaEntry(date: .now, snapshot: snapshot ?? repo.cachedSnapshot())
            // Refresh every 30 minutes
            let next = Calendar.current.date(byAdding: .minute, value: 30, to: .now)!
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }
}

struct SofiaWidgetEntryView: View {
    let entry: SofiaEntry

    var body: some View {
        if let snap = entry.snapshot {
            VStack(alignment: .leading) {
                Text("Sofia Wind")
                    .font(.caption2).foregroundStyle(.secondary)
                Text("\(snap.latestMW, specifier: "%.0f") MW")
                    .font(.title2.bold())
                Text("\(snap.capacityFactor * 100, specifier: "%.1f")%")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding()
        } else {
            Text("—")
        }
    }
}

@main
struct SofiaWidget: Widget {
    let kind = "SofiaWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: SofiaProvider()) { entry in
            SofiaWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Sofia Wind Farm")
        .description("Current generation output.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
```

---

## 11. Settings screen

```swift
// SettingsView.swift

struct SettingsView: View {
    @State private var bmuText = AppSettings.shared.bmuIds.joined(separator: ", ")
    @State private var apiMode = AppSettings.shared.apiMode

    var body: some View {
        Form {
            Section("BMU IDs (comma-separated)") {
                TextField("SOFWO-11, SOFWO-12, …", text: $bmuText)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            Section("API") {
                Picker("Data source", selection: $apiMode) {
                    Text("Legacy (Robinhawkes)").tag("legacy")
                    Text("Sofia server").tag("sofia")
                }
                .pickerStyle(.segmented)
            }

            Section {
                Button("Save") {
                    let ids = bmuText.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                    AppSettings.shared.bmuIds = ids
                    AppSettings.shared.apiMode = apiMode
                }
                Button("Reset to defaults", role: .destructive) {
                    AppSettings.shared.bmuIds = AppSettings.defaultBmuIds
                    bmuText = AppSettings.defaultBmuIds.joined(separator: ", ")
                }
            }
        }
        .navigationTitle("Settings")
    }
}
```

---

## 12. Key differences to be aware of

| Topic               | Android                              | iOS                                               |
|---------------------|--------------------------------------|---------------------------------------------------|
| Min OS              | Android 8+ (API 26)                  | iOS 16+ recommended (Swift Charts, `@Observable`) |
| Background limits   | WorkManager handles Doze mode        | iOS is stricter; BGTaskScheduler is best-effort   |
| Widget data sharing | No App Group needed                  | **App Group required** for shared `UserDefaults`  |
| Chart library       | MPAndroidChart (3rd party)           | Swift Charts (built-in, iOS 16+)                  |
| UTC parsing         | `Instant.parse` + `replace(' ','T')` | `ISO8601DateFormatter` handles both variants      |
| Reactive state      | `StateFlow` + coroutines             | `@Observable` + `async/await`                     |
| Dependency injection| Constructor params (ViewModel)       | Same pattern works; or use `@Environment`         |

---

## 13. Recommended project structure

```
SofiaApp/
├── SofiaApp.swift            # @main
├── Data/
│   ├── AppSettings.swift
│   ├── Models.swift
│   ├── EnergyApiClient.swift
│   ├── SofiaApiClient.swift
│   └── SofiaRepository.swift
├── UI/
│   ├── Dashboard/
│   │   ├── DashboardView.swift
│   │   ├── DashboardViewModel.swift
│   │   └── GenerationChartView.swift
│   └── Settings/
│       └── SettingsView.swift
└── Widget/
    └── SofiaWidget.swift
```

---

*Migration guide written against the Android source in this repository (April 2026). API endpoints and BMU IDs are unchanged — the iOS app consumes the same `https://sofia.lemarc.fr` and `https://energy-api.robinhawkes.com` services.*
