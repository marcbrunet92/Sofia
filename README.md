# Sofia Production — Android App & API Server

Real-time and historical generation monitoring for **Sofia Offshore Wind Farm** (1,400 MW · 100 turbines).

---

## Repository layout

```
Sofia/
├── app/                        # Android application (Kotlin + XML views)
│   └── src/main/java/…
│       ├── data/               # Data layer: models, AppSettings, repositories
│       ├── ui/dashboard/       # Dashboard fragment + ViewModel
│       ├── ui/settings/        # Settings fragment
│       └── widget/             # Home-screen widget
├── server/                     # FastAPI caching server (sofia.lemarc.fr)
│   ├── main.py                 # FastAPI routes
│   ├── database.py             # Async SQLite helpers
│   ├── fetcher.py              # Upstream fetch + scheduled backfill
│   ├── requirements.txt
│   └── systemd/
│       ├── sofia.service       # systemd unit file
│       └── sofia.nginx.conf    # nginx reverse-proxy config
└── sofia_api_summary.md        # Upstream API documentation
```

---

## Android app

### Features

| Feature | Description |
|---------|-------------|
| Live output | Current MW, capacity factor, source badge (Metered / Forecast) |
| History chart | 1–90 day generation curve with slider (up to 14 days on legacy API) |
| Status banner | Operational / Planned maintenance / Unplanned outage |
| Records card | All-time, 90-day, 7-day peak MW (Sofia API only) |
| Home-screen widget | Compact live output ring, refreshed every 30 min via WorkManager |

### API source setting

In **Settings → Source de données** you can choose between two data sources:

| Option | Endpoint | History depth |
|--------|----------|--------------|
| **API Robinhawkes** | `energy-api.robinhawkes.com` | 14 days |
| **API Sofia** | `sofia.lemarc.fr` | Accumulated since server deployment |

The Settings screen fetches the exact number of available days from the Sofia server and displays it next to the option.

### Build commands (Windows)

```bat
# Debug APK
./gradlew.bat :app:assembleDebug

# Unit tests
./gradlew.bat :app:testDebugUnitTest

# Lint
./gradlew.bat :app:lintDebug
```

---

## Sofia API server

The server caches generation data from the upstream Robinhawkes/Elexon BMRS API so the Android app can access longer history than the upstream's 14-day window.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Liveness check + data freshness timestamps |
| `GET` | `/latest-date` | Oldest / latest stored date + `total_days` |
| `GET` | `/generation` | Aggregated generation for a date range & BMU set |
| `GET` | `/records` | All-time, 90-day, 7-day peak MW |

#### `/generation` parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `from_date` | ✓ | ISO 8601 start datetime (inclusive) |
| `to_date` | ✓ | ISO 8601 end datetime (exclusive) |
| `bmu_ids` | — | BMU IDs to aggregate (default: all four Sofia BMUs) |

### Data refresh strategy

- **On first startup**: backfill the last 365 days from the upstream API (≈ 3 minutes with rate limiting).
- **Every 48 hours**: fetch the last 3 days to pull in newly confirmed metered (`b1610`) data.
- The database never shrinks — all historical data is kept.

### Server deployment

> Repository is cloned to `/home/Marc/Web/Sofia/`

#### 1. Create virtualenv & install dependencies

```bash
cd /home/Marc/Web/Sofia/server
python3 -m venv venv
venv/bin/pip install -r requirements.txt
```

#### 2. Install systemd service

```bash
sudo cp systemd/sofia.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable sofia
sudo systemctl start sofia
```

#### 3. Configure nginx

```bash
sudo cp systemd/sofia.nginx.conf /etc/nginx/sites-available/sofia
sudo ln -s /etc/nginx/sites-available/sofia /etc/nginx/sites-enabled/sofia
# Add your SSL settings to sofia.nginx.conf, then:
sudo nginx -t && sudo systemctl reload nginx
```

#### 4. Verify

```bash
curl https://sofia.lemarc.fr/health
```

---

## BMU IDs

| National Grid ID | Elexon BM Unit |
|------------------|----------------|
| `SOFWO-11` | `T_SOFOW-11` |
| `SOFWO-12` | `T_SOFOW-12` |
| `SOFWO-21` | `T_SOFOW-21` |
| `SOFWO-22` | `T_SOFOW-22` |

Total installed capacity: **1,400 MW** (100 × 14 MW Siemens Gamesa turbines)
