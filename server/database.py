"""Async SQLite helpers for the Sofia generation data store."""

from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import aiosqlite

DB_PATH: str = os.environ.get(
    "SOFIA_DB_PATH", str(Path(__file__).parent / "sofia.db")
)

_CREATE_TABLE = """
CREATE TABLE IF NOT EXISTS generation (
    bmu_id    TEXT NOT NULL,
    time_from TEXT NOT NULL,
    time_to   TEXT NOT NULL,
    level_mw  REAL NOT NULL DEFAULT 0.0,
    source    TEXT NOT NULL DEFAULT 'pn',
    PRIMARY KEY (bmu_id, time_from)
);
CREATE INDEX IF NOT EXISTS idx_gen_time ON generation (time_from);
"""


async def init_db() -> None:
    """Create the database schema if it does not already exist."""
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executescript(_CREATE_TABLE)
        await db.commit()


async def upsert_slots(slots: list[dict]) -> None:
    """Insert or replace a list of generation slot dicts."""
    if not slots:
        return
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executemany(
            """
            INSERT OR REPLACE INTO generation (bmu_id, time_from, time_to, level_mw, source)
            VALUES (:bmu_id, :time_from, :time_to, :level_mw, :source)
            """,
            slots,
        )
        await db.commit()


async def get_latest_date() -> str | None:
    """Return the most recent time_from stored in the DB (ISO string), or None."""
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute("SELECT MAX(time_from) FROM generation") as cur:
            row = await cur.fetchone()
    return row[0] if row else None


async def get_oldest_date() -> str | None:
    """Return the oldest time_from stored in the DB (ISO string), or None."""
    async with aiosqlite.connect(DB_PATH) as db:
        async with db.execute("SELECT MIN(time_from) FROM generation") as cur:
            row = await cur.fetchone()
    return row[0] if row else None


async def query_generation(
    from_date: str,
    to_date: str,
    bmu_ids: list[str],
) -> list[dict]:
    """
    Return aggregated generation (sum across bmu_ids) for [from_date, to_date).
    Results are ordered by time_from ascending.
    """
    placeholders = ",".join("?" * len(bmu_ids))
    sql = f"""
        SELECT time_from,
               time_to,
               SUM(level_mw) AS total_mw,
               MAX(source)   AS source
        FROM   generation
        WHERE  bmu_id   IN ({placeholders})
          AND  time_from >= ?
          AND  time_from <  ?
        GROUP  BY time_from, time_to
        ORDER  BY time_from
    """
    params = [*bmu_ids, from_date, to_date]
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(sql, params) as cur:
            rows = await cur.fetchall()
    return [dict(r) for r in rows]


async def query_records(bmu_ids: list[str]) -> dict:
    """
    Return peak generation statistics:
    - all-time maximum
    - maximum over the last 90 days
    - maximum over the last 7 days
    """
    placeholders = ",".join("?" * len(bmu_ids))
    now = datetime.now(timezone.utc)
    cutoff_90 = (now - timedelta(days=90)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    cutoff_7 = (now - timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%S.000Z")

    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row

        async with db.execute(
            f"""
            SELECT time_from, SUM(level_mw) AS total_mw
            FROM   generation
            WHERE  bmu_id IN ({placeholders})
            GROUP  BY time_from
            ORDER  BY total_mw DESC
            LIMIT  1
            """,
            bmu_ids,
        ) as cur:
            all_time = await cur.fetchone()

        async with db.execute(
            f"""
            SELECT time_from, SUM(level_mw) AS total_mw
            FROM   generation
            WHERE  bmu_id IN ({placeholders})
              AND  time_from >= ?
            GROUP  BY time_from
            ORDER  BY total_mw DESC
            LIMIT  1
            """,
            [*bmu_ids, cutoff_90],
        ) as cur:
            days_90 = await cur.fetchone()

        async with db.execute(
            f"""
            SELECT time_from, SUM(level_mw) AS total_mw
            FROM   generation
            WHERE  bmu_id IN ({placeholders})
              AND  time_from >= ?
            GROUP  BY time_from
            ORDER  BY total_mw DESC
            LIMIT  1
            """,
            [*bmu_ids, cutoff_7],
        ) as cur:
            days_7 = await cur.fetchone()

    return {
        "all_time_max_mw":   all_time["total_mw"]   if all_time else 0.0,
        "all_time_max_date": all_time["time_from"]   if all_time else None,
        "days_90_max_mw":    days_90["total_mw"]     if days_90  else 0.0,
        "days_90_max_date":  days_90["time_from"]    if days_90  else None,
        "days_7_max_mw":     days_7["total_mw"]      if days_7   else 0.0,
        "days_7_max_date":   days_7["time_from"]     if days_7   else None,
    }
