"""Sofia Wind Farm — FastAPI server.

Endpoints
---------
GET /health           Basic liveness + data freshness info.
GET /latest-date      Date range of stored data (oldest / latest / total_days).
GET /generation       Aggregated generation for a date range and BMU selection.
GET /records          Peak generation statistics (all-time, 90-day, 7-day).
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware

from database import (
    get_latest_date,
    get_oldest_date,
    init_db,
    query_generation,
    query_records,
)
from fetcher import BMU_IDS, backfill, refresh

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
)
log = logging.getLogger(__name__)

_scheduler = AsyncIOScheduler()


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    await init_db()
    # Start backfill in the background so the server responds immediately.
    asyncio.create_task(backfill())
    # Refresh every 15 minutes — Robinhawkes updates data every 15 minutes.
    _scheduler.add_job(refresh, "interval", minutes=15, id="sofia_refresh")
    _scheduler.start()
    yield
    _scheduler.shutdown()


app = FastAPI(
    title="Sofia Wind Farm API",
    description=(
        "Aggregated generation data for Sofia Offshore Wind Farm, "
        "cached from the Robinhawkes / Elexon BMRS API."
    ),
    version="1.0.0",
    lifespan=_lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET"],
    allow_headers=["*"],
)


# ── Routes ────────────────────────────────────────────────────────────────────


@app.get("/health", summary="Server health and data freshness")
async def health() -> dict:
    """Returns `ok` with the latest and oldest data timestamps."""
    return {
        "status": "ok",
        "latest_data_date": await get_latest_date(),
        "oldest_data_date": await get_oldest_date(),
        "server_time_utc": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/latest-date", summary="Data date range and total history depth")
async def latest_date_info() -> dict:
    """
    Returns the oldest and latest stored data timestamps plus `total_days`,
    which is used by the Android app to configure the chart slider maximum.
    """
    latest = await get_latest_date()
    oldest = await get_oldest_date()
    total_days = 0
    if latest and oldest:
        try:
            d1 = datetime.fromisoformat(latest.replace("Z", "+00:00"))
            d0 = datetime.fromisoformat(oldest.replace("Z", "+00:00"))
            total_days = max(1, int((d1 - d0).days) + 1)
        except ValueError:
            pass
    return {
        "latest_date": latest,
        "oldest_date": oldest,
        "total_days":  total_days,
    }


@app.get("/generation", summary="Aggregated generation for a date range")
async def generation(
    from_date: str = Query(
        ...,
        description="ISO 8601 start datetime (inclusive), e.g. 2026-04-01T00:00:00Z",
        example="2026-04-01T00:00:00Z",
    ),
    to_date: str = Query(
        ...,
        description="ISO 8601 end datetime (exclusive), e.g. 2026-05-01T00:00:00Z",
        example="2026-05-01T00:00:00Z",
    ),
    bmu_ids: list[str] = Query(
        default=BMU_IDS,
        description="BMU IDs to aggregate. Defaults to all four Sofia BMUs.",
    ),
) -> list[dict]:
    """
    Returns half-hourly aggregated generation (sum across the requested BMUs)
    for the given date range, ordered by `time_from` ascending.

    Each item has the shape:
    ```json
    { "time_from": "...", "time_to": "...", "total_mw": 450.5, "source": "b1610" }
    ```
    """
    return await query_generation(from_date, to_date, bmu_ids)


@app.get("/records", summary="Peak generation statistics")
async def records(
    bmu_ids: list[str] = Query(
        default=BMU_IDS,
        description="BMU IDs to include. Defaults to all four Sofia BMUs.",
    ),
) -> dict:
    """
    Returns the highest single 30-minute generation value (in MW) for three windows:

    - **all_time** — entire history in the database
    - **days_90**  — last 90 days
    - **days_7**   — last 7 days

    Each window returns `*_max_mw` and `*_max_date` (the timestamp of that peak).
    """
    return await query_records(bmu_ids)
