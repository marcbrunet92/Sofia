"""Fetch generation data from the upstream Robinhawkes Energy API and persist it."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

import httpx

from database import get_latest_date, upsert_slots

log = logging.getLogger(__name__)

UPSTREAM = "https://energy-api.robinhawkes.com"
BMU_IDS: list[str] = ["SOFWO-11", "SOFWO-12", "SOFWO-21", "SOFWO-22"]

# Fixed start date for backfill — fetch all data from the farm's commissioning start
BACKFILL_START = datetime(2026, 4, 1, tzinfo=timezone.utc)

# Polite delay between upstream requests (seconds)
_REQUEST_DELAY = 0.5

# Guard against concurrent fetches
_fetch_lock = asyncio.Lock()


async def _fetch_day(client: httpx.AsyncClient, date_iso: str) -> list[dict]:
    """Return slot dicts for one day across all BMU IDs."""
    params: list[tuple[str, str]] = [("date", date_iso)] + [
        ("bmrsids", b) for b in BMU_IDS
    ]
    resp = await client.get(
        f"{UPSTREAM}/generation-history",
        params=params,
        timeout=25.0,
    )
    resp.raise_for_status()
    body = resp.json()

    slots: list[dict] = []
    for bmu_id in BMU_IDS:
        bmu_data = body.get(bmu_id) or {}
        for slot in bmu_data.get("generation") or []:
            slots.append(
                {
                    "bmu_id":    bmu_id,
                    "time_from": slot["timeFrom"].replace(" ", "T"),
                    "time_to":   slot["timeTo"].replace(" ", "T"),
                    "level_mw":  slot.get("levelTo") or 0.0,
                    "source":    slot.get("source") or "pn",
                }
            )
    return slots


async def backfill() -> None:
    """
    Fill the database with historical data.

    If the database is empty, fetch BACKFILL_DAYS of history.
    If data already exists, fetch only from the most recent stored date to today.
    """
    async with _fetch_lock:
        now = datetime.now(timezone.utc)
        latest = await get_latest_date()

        if latest:
            start = datetime.fromisoformat(
                latest.replace("Z", "+00:00")
            ).replace(tzinfo=timezone.utc)
        else:
            start = BACKFILL_START

        days_needed = max(1, int((now - start).total_seconds() / 86400) + 1)
        log.info("Backfill: fetching %d days from %s", days_needed, start.date())

        async with httpx.AsyncClient() as client:
            for i in range(days_needed):
                target = now - timedelta(days=days_needed - 1 - i)
                date_iso = target.strftime("%Y-%m-%dT%H:%M:%S.000Z")
                try:
                    slots = await _fetch_day(client, date_iso)
                    await upsert_slots(slots)
                    if (i + 1) % 30 == 0:
                        log.info(
                            "Backfill progress: %d / %d days", i + 1, days_needed
                        )
                except (httpx.HTTPError, httpx.TimeoutException, OSError) as exc:
                    log.warning("Backfill skip %s: %s", date_iso, exc)
                except Exception as exc:
                    log.error("Unexpected backfill error for %s: %r", date_iso, exc)
                await asyncio.sleep(_REQUEST_DELAY)

        log.info("Backfill complete (%d days attempted)", days_needed)


async def refresh() -> None:
    """
    Scheduled task: fetch the last 3 days to capture any newly confirmed
    metered (b1610) data that was not yet available at the previous fetch.
    """
    if _fetch_lock.locked():
        log.info("Refresh skipped — backfill still running")
        return

    async with _fetch_lock:
        log.info("Running scheduled refresh")
        now = datetime.now(timezone.utc)
        async with httpx.AsyncClient() as client:
            for i in range(3):
                target = now - timedelta(days=i)
                date_iso = target.strftime("%Y-%m-%dT%H:%M:%S.000Z")
                try:
                    slots = await _fetch_day(client, date_iso)
                    await upsert_slots(slots)
                except (httpx.HTTPError, httpx.TimeoutException, OSError) as exc:
                    log.warning("Refresh skip %s: %s", date_iso, exc)
                except Exception as exc:
                    log.error("Unexpected refresh error for %s: %r", date_iso, exc)
                await asyncio.sleep(_REQUEST_DELAY)
        log.info("Refresh complete")
