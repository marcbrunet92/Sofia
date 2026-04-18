# Sofia Wind Farm App — API Layer Summary

## Overview

The app consumes two REST endpoints from **Robin Hawkes' Energy API** (`energy-api.robinhawkes.com`), which is a clean wrapper around Elexon's Balancing Mechanism Reporting Service (BMRS). All generation data is structured around **half-hourly settlement periods** (UK grid standard).

Sofia Offshore Wind Farm has **4 BMU (Balancing Mechanism Unit) IDs** registered with Elexon:

| National Grid ID | Elexon BM Unit    |
|------------------|-------------------|
| `SOFWO-11`       | `T_SOFOW-11`      |
| `SOFWO-12`       | `T_SOFOW-12`      |
| `SOFWO-21`       | `T_SOFOW-21`      |
| `SOFWO-22`       | `T_SOFOW-22`      |

Total installed capacity: **1,400 MW** (100 × 14 MW Siemens Gamesa turbines)

---

## Endpoint 1 — Generation History

### Purpose
Returns historical and near-real-time generation output (MW) per BMU, broken into 30-minute settlement periods. Covers the last 48 settlement periods (~24 hours) relative to the `date` parameter.

### URL Structure

```
GET https://energy-api.robinhawkes.com/generation-history
  ?date={ISO8601_timestamp}
  &bmrsids={BMU_ID}
  &bmrsids={BMU_ID}
  ...
```

### Parameters

| Parameter  | Type     | Required | Description                                                              |
|------------|----------|----------|--------------------------------------------------------------------------|
| `date`     | ISO 8601 | Yes      | The reference datetime. Returns the 48 periods ending at this timestamp. |
| `bmrsids`  | string   | Yes      | BMU ID to include. Repeat for each unit (up to 4 for Sofia).            |

### Example call (all 4 Sofia BMUs)

```
GET https://energy-api.robinhawkes.com/generation-history
  ?date=2026-04-18T15:30:00.000Z
  &bmrsids=SOFWO-11
  &bmrsids=SOFWO-12
  &bmrsids=SOFWO-21
  &bmrsids=SOFWO-22
```

### Response structure

```json
{
  "SOFWO-22": {
    "bmUnit": "T_SOFOW-22",
    "nationalGridBmUnit": "SOFWO-22",
    "generation": [
      {
        "timeFrom": "2026-04-18 15:00:00.000Z",
        "timeTo":   "2026-04-18 15:30:00.000Z",
        "levelTo":  0,
        "capacityMW": null,
        "curtailmentMW": null,
        "curtailmentNonSOMW": null,
        "curtailmentStartLevelMW": null,
        "turnUpMW": null,
        "turnUpNonSOMW": null,
        "operatingMode": null,
        "source": "pn"
      },
      ...
    ]
  },
  "SOFWO-21": { ... },
  "SOFWO-12": { ... },
  "SOFWO-11": { ... }
}
```

### Key fields

| Field          | Type    | Description                                                                 |
|----------------|---------|-----------------------------------------------------------------------------|
| `timeFrom`     | string  | Start of the 30-minute settlement period (UTC)                              |
| `timeTo`       | string  | End of the 30-minute settlement period (UTC)                                |
| `levelTo`      | number  | **Generation output in MW** at the end of the period — the primary value    |
| `capacityMW`   | number? | Registered capacity for this period (null if not submitted)                 |
| `curtailmentMW`| number? | MW curtailed by the System Operator (null if no curtailment instruction)    |
| `source`       | string  | Data source: `"b1610"` = reconciled metered; `"pn"` = Physical Notification |

### Source field explained

- **`"b1610"`** — Actual metered generation from Elexon's B1610 report. Reconciled and reliable. Available with ~30-minute delay, confirmed up to ~5 days back.
- **`"pn"`** — Physical Notification (forecast). This is what the generator declared it *intended* to produce, submitted 1 hour before each period. Used to fill in the most recent periods before metered data is confirmed.

### Computing total farm output

The response gives per-BMU data. Sum `levelTo` across all 4 BMUs for each time slot to get the total farm generation:

```dart
double totalMW = ['SOFWO-11', 'SOFWO-12', 'SOFWO-21', 'SOFWO-22']
    .map((id) => data[id]?['generation'][0]['levelTo'] ?? 0)
    .fold(0.0, (sum, v) => sum + v);
```

---

## Endpoint 2 — Notifications

### Purpose
Returns active **availability notices** (REMIT notifications) filed with Elexon for a given set of BMUs. These indicate planned or unplanned outages, curtailment events, or reduced availability windows. Essential for displaying a status banner in the app.

### URL Structure

```
GET https://energy-api.robinhawkes.com/notifications
  ?date={ISO8601_timestamp}
  &bmrsids={BMU_ID}
  &bmrsids={BMU_ID}
  ...
```

### Parameters

Same as `/generation-history` — `date` and repeated `bmrsids`.

### Example call

```
GET https://energy-api.robinhawkes.com/notifications
  ?date=2026-04-18T15:30:00.000Z
  &bmrsids=SOFWO-11
  &bmrsids=SOFWO-12
  &bmrsids=SOFWO-21
  &bmrsids=SOFWO-22
```

### Response structure

```json
{
  "SOFWO-22": {
    "bmUnit": "T_SOFOW-22",
    "nationalGridBmUnit": "SOFWO-22",
    "notifications": [
      {
        "documentID": "48X000000000392E-NGET-RMT-00209313",
        "revisionNumber": 126,
        "timeFrom": "2025-12-09T05:00:00.000Z",
        "timeTo":   "2026-05-03T04:00:00.000Z",
        "levels": [0],
        "reasonCode": "Ambient Conditions",
        "reasonDescription": "Estimated End Date / Time changed to 03 May 2026 04:00 (GMT); Detailed MEL profile has changed",
        "messageHeading": "Actual Availability of Generation Unit",
        "eventType": "Production unavailability",
        "unavailabilityType": "Unplanned"
      }
    ]
  }
}
```

### Key fields

| Field                | Type     | Description                                                                 |
|----------------------|----------|-----------------------------------------------------------------------------|
| `documentID`         | string   | Unique REMIT document identifier                                            |
| `revisionNumber`     | integer  | Version of the notice (increments each update)                              |
| `timeFrom`           | string   | Start of the unavailability window (UTC)                                    |
| `timeTo`             | string   | Expected end of the unavailability window (UTC)                             |
| `levels`             | number[] | Expected generation level(s) during the period (MW). `[0]` = fully offline |
| `reasonCode`         | string   | Short reason category (e.g. `"Ambient Conditions"`, `"Planned Maintenance"`)|
| `reasonDescription`  | string   | Full human-readable description of the notice                               |
| `eventType`          | string   | Type of event (e.g. `"Production unavailability"`)                          |
| `unavailabilityType` | string   | `"Planned"` or `"Unplanned"`                                                |

### Current active notice

As of April 2026, SOFWO-22 has an active unplanned unavailability notice:

- **Reason:** Ambient Conditions (commissioning phase)
- **Period:** 9 Dec 2025 → 3 May 2026
- **Level:** 0 MW (fully offline)

This explains why `levelTo` is 0 across all periods in generation history. The farm is still in pre-commercial commissioning.

---

## Fetching Multiple Days (History Beyond 24h)

The API returns 48 periods (~24h) per call. To fetch longer history, call the endpoint once per day and merge results:

```
date = today       → returns periods for today
date = today - 1d  → returns periods for yesterday
date = today - 7d  → returns periods for 7 days ago
```

One HTTP request per day is required. For 30 days of history, that is 30 sequential requests.

---

## Recommended Polling Strategy

| Data type          | Endpoint               | Frequency     | Rationale                                              |
|--------------------|------------------------|---------------|--------------------------------------------------------|
| Current generation | `/generation-history`  | Every 30 min  | Data updates once per settlement period                |
| Status / outages   | `/notifications`       | Every 6 hours | Notices update infrequently; change is rare            |
| Historical chart   | `/generation-history`  | On demand     | Triggered by user scrolling back in time               |

---

## Flutter Integration Sketch

```dart
class SofiaApiService {
  static const _base = 'https://energy-api.robinhawkes.com';
  static const _bmus = ['SOFWO-11', 'SOFWO-12', 'SOFWO-21', 'SOFWO-22'];

  /// Fetch latest 48 periods and sum all 4 BMUs per slot
  Future<List<GenerationSlot>> fetchGeneration(DateTime date) async {
    final query = [
      'date=${date.toUtc().toIso8601String()}',
      ..._bmus.map((id) => 'bmrsids=$id'),
    ].join('&');

    final res = await http.get(Uri.parse('$_base/generation-history?$query'));
    final body = jsonDecode(res.body) as Map<String, dynamic>;

    // Aggregate across all BMUs
    final Map<String, double> totals = {};
    for (final bmuId in _bmus) {
      final slots = (body[bmuId]?['generation'] as List?) ?? [];
      for (final slot in slots) {
        final t = slot['timeFrom'] as String;
        totals[t] = (totals[t] ?? 0) + ((slot['levelTo'] as num?)?.toDouble() ?? 0);
      }
    }

    return totals.entries
        .map((e) => GenerationSlot(time: DateTime.parse(e.key), mw: e.value))
        .toList()
          ..sort((a, b) => a.time.compareTo(b.time));
  }

  /// Fetch active availability notifications
  Future<List<Notification>> fetchNotifications(DateTime date) async {
    final query = [
      'date=${date.toUtc().toIso8601String()}',
      ..._bmus.map((id) => 'bmrsids=$id'),
    ].join('&');

    final res = await http.get(Uri.parse('$_base/notifications?$query'));
    final body = jsonDecode(res.body) as Map<String, dynamic>;

    final List<Notification> all = [];
    for (final bmuId in _bmus) {
      final notices = (body[bmuId]?['notifications'] as List?) ?? [];
      all.addAll(notices.map((n) => Notification.fromJson(bmuId, n)));
    }
    return all;
  }
}
```

---

## Data Model

```dart
class GenerationSlot {
  final DateTime time;   // UTC start of the 30-min period
  final double mw;       // Total MW across all 4 BMUs

  double get capacityFactor => mw / 1400.0; // Sofia = 1400 MW installed
}

class AvailabilityNotice {
  final String bmuId;
  final String documentId;
  final int revisionNumber;
  final DateTime timeFrom;
  final DateTime timeTo;
  final String reasonCode;
  final String reasonDescription;
  final String unavailabilityType; // "Planned" | "Unplanned"
  final double levelMW;            // Expected output during outage (usually 0)
}
```

---

## Error Handling

| Scenario                   | Behaviour to implement                                                     |
|----------------------------|----------------------------------------------------------------------------|
| Network timeout            | Retry once after 5s, then show cached data with a stale-data indicator     |
| Empty `generation` array   | Farm offline or data not yet available — show 0 MW with "no data" label    |
| `levelTo` is null          | Treat as 0 MW                                                              |
| Non-200 HTTP response      | Log error, display last cached value                                       |
| No notifications returned  | Normal state — show green "operational" status badge                       |

---

*Last updated: April 2026. BMU IDs confirmed from live API response.*
