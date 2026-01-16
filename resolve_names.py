#!/usr/bin/env python3
"""Resolve and refresh Minecraft player names in player_profile using Mojang endpoints.

Design goals (100k+ friendly):
- Never block normal page requests: this runs as a background maintenance job (cron).
- Only touch a bounded number of rows per run (--max-per-run).
- Rate-limit Mojang calls (--sleep-ms).
- Support refreshing names (name_checked_at < now - refresh_days) to catch name changes.

Configuration:
- DB credentials are read from environment variables (recommended):
    STATS_DB_HOST, STATS_DB_PORT, STATS_DB_NAME, STATS_DB_USER, STATS_DB_PASS
  or via CLI args.

Typical usage (after importer):
  python3 resolve_names.py --refresh-days 30 --max-per-run 1500 --sleep-ms 150
"""

from __future__ import annotations

import argparse
import json
import os
import time
import uuid as uuidlib
from dataclasses import dataclass
from typing import Any, Optional
from urllib.request import Request, urlopen

import pymysql  # type: ignore
import pymysql.cursors  # type: ignore


@dataclass(frozen=True)
class Candidate:
    uuid_bin: bytes
    name: Optional[str]
    name_source: Optional[str]
    name_checked_at: Optional[str]


def connect_db(args: argparse.Namespace):
    host = args.db_host or os.environ.get("STATS_DB_HOST", "")
    user = args.db_user or os.environ.get("STATS_DB_USER", "")
    password = args.db_pass or os.environ.get("STATS_DB_PASS", "")
    db = args.db_name or os.environ.get("STATS_DB_NAME", "")
    port = int(args.db_port or os.environ.get("STATS_DB_PORT", "3306"))

    if not host or not user or not db:
        raise SystemExit(
            "DB config missing. Set env STATS_DB_HOST/USER/NAME (and PASS) or pass CLI args."
        )

    return pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=db,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.Cursor,
        connect_timeout=10,
        read_timeout=25,
        write_timeout=25,
    )


def table_has_column(conn, table: str, column: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = %s
              AND column_name = %s
            LIMIT 1
            """,
            (table, column),
        )
        return cur.fetchone() is not None


def acquire_db_lock(conn, lock_name: str, timeout_s: int) -> bool:
    with conn.cursor() as cur:
        cur.execute("SELECT GET_LOCK(%s, %s)", (lock_name, int(timeout_s)))
        row = cur.fetchone()
        return bool(row and row[0] == 1)


def release_db_lock(conn, lock_name: str) -> None:
    with conn.cursor() as cur:
        cur.execute("DO RELEASE_LOCK(%s)", (lock_name,))
    conn.commit()


def get_active_run_id(conn) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT active_run_id FROM site_state WHERE id=1")
        row = cur.fetchone()
        if not row or row[0] is None:
            raise SystemExit("site_state.active_run_id is NULL")
        return int(row[0])


def http_get_json(url: str, timeout_s: int = 12) -> Optional[Any]:
    req = Request(url, headers={"User-Agent": "stats-name-resolver/2.0"})
    try:
        with urlopen(req, timeout=timeout_s) as resp:
            data = resp.read()
        if not data:
            return None
        return json.loads(data.decode("utf-8", errors="replace"))
    except Exception:
        return None


def mojang_name_for_uuid(u_hex_undashed: str) -> Optional[str]:
    """Return current name for UUID hex (undashed), or None if unknown."""

    # 1) Sessionserver: current profile incl. current name
    js = http_get_json(f"https://sessionserver.mojang.com/session/minecraft/profile/{u_hex_undashed}")
    if isinstance(js, dict):
        name = js.get("name")
        if isinstance(name, str) and name:
            return name[:16]

    # 2) Name history endpoint: last element is the current/latest name
    js2 = http_get_json(f"https://api.mojang.com/user/profiles/{u_hex_undashed}/names")
    if isinstance(js2, list) and js2:
        last = js2[-1]
        if isinstance(last, dict):
            name = last.get("name")
            if isinstance(name, str) and name:
                return name[:16]

    return None


def fetch_candidates(
    conn,
    run_id: int,
    max_per_run: int,
    refresh_days: int,
    has_source: bool,
    has_checked: bool,
):
    missing_expr = "(name IS NULL OR name='' OR name='Unknown' OR name REGEXP '^[0-9a-f]{12}$')"

    where_parts = [missing_expr]
    params = [run_id]

    refresh_enabled = refresh_days > 0 and has_checked
    if refresh_enabled:
        where_parts.append("(name_checked_at IS NULL OR name_checked_at < (NOW() - INTERVAL %s DAY))")
        params.append(int(refresh_days))

    where_sql = " OR ".join(where_parts)

    # Prefer using name_source to catch placeholders cheaply
    # - treat 'unknown' like missing (will be backfilled)
    source_missing = ""
    if has_source:
        source_missing = "(name_source IN ('fallback','unknown')) OR "

    order_missing = f"CASE WHEN ({source_missing}{missing_expr}) THEN 1 ELSE 0 END DESC"

    cols = ["uuid", "name"]
    if has_source:
        cols.append("name_source")
    if has_checked:
        cols.append("name_checked_at")

    sql = f"""
        SELECT {', '.join(cols)}
        FROM player_profile
        WHERE run_id=%s AND ({where_sql})
        ORDER BY
          {order_missing},
          COALESCE(name_checked_at, '1970-01-01') ASC,
          uuid ASC
        LIMIT %s
    """

    params.append(int(max_per_run))

    with conn.cursor() as cur:
        cur.execute(sql, tuple(params))
        rows = cur.fetchall()

    out: list[Candidate] = []
    for r in rows:
        if not r:
            continue
        u_bin = r[0]
        if not isinstance(u_bin, (bytes, bytearray)) or len(u_bin) != 16:
            continue
        name = r[1] if isinstance(r[1], str) else None
        name_source = None
        name_checked_at = None
        if has_source:
            name_source = r[2] if len(r) > 2 and isinstance(r[2], str) else None
        if has_checked:
            name_checked_at = r[-1] if r[-1] is not None else None
        out.append(Candidate(uuid_bin=bytes(u_bin), name=name, name_source=name_source, name_checked_at=name_checked_at))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()

    ap.add_argument("--db-host")
    ap.add_argument("--db-port")
    ap.add_argument("--db-name")
    ap.add_argument("--db-user")
    ap.add_argument("--db-pass")

    ap.add_argument("--run-id", type=int, default=0, help="Default: site_state.active_run_id")
    ap.add_argument("--max-per-run", type=int, default=1500)
    ap.add_argument("--sleep-ms", type=int, default=150)
    ap.add_argument("--refresh-days", type=int, default=30)

    ap.add_argument("--lock-name", default="mc_stats_import")
    ap.add_argument("--lock-timeout", type=int, default=0, help="Seconds to wait for DB lock (0 = no wait)")

    args = ap.parse_args()

    conn = connect_db(args)
    lock_acquired = False

    try:
        lock_acquired = acquire_db_lock(conn, args.lock_name, args.lock_timeout)
        if not lock_acquired:
            print(f"resolve_names: could not acquire lock '{args.lock_name}'")
            return 10

        run_id = int(args.run_id) if int(args.run_id) > 0 else get_active_run_id(conn)

        has_source = table_has_column(conn, "player_profile", "name_source")
        has_checked = table_has_column(conn, "player_profile", "name_checked_at")

        candidates = fetch_candidates(
            conn,
            run_id=run_id,
            max_per_run=int(args.max_per_run),
            refresh_days=int(args.refresh_days),
            has_source=has_source,
            has_checked=has_checked,
        )

        total = len(candidates)
        resolved = 0
        failed = 0

        updates_ok = []
        updates_fail = []

        for c in candidates:
            u_hex = uuidlib.UUID(bytes=c.uuid_bin).hex
            new_name = mojang_name_for_uuid(u_hex)

            if new_name:
                resolved += 1
                updates_ok.append((new_name, new_name.lower(), run_id, c.uuid_bin))
            else:
                failed += 1
                # Mark as checked anyway to avoid hammering the same UUID every run.
                if has_checked:
                    updates_fail.append((run_id, c.uuid_bin))

            if int(args.sleep_ms) > 0:
                time.sleep(int(args.sleep_ms) / 1000.0)

        with conn.cursor() as cur:
            if updates_ok:
                if has_source and has_checked:
                    cur.executemany(
                        """
                        UPDATE player_profile
                        SET name=%s, name_lc=%s, name_source='mojang', name_checked_at=NOW()
                        WHERE run_id=%s AND uuid=%s
                        """,
                        updates_ok,
                    )
                elif has_source:
                    cur.executemany(
                        """
                        UPDATE player_profile
                        SET name=%s, name_lc=%s, name_source='mojang'
                        WHERE run_id=%s AND uuid=%s
                        """,
                        updates_ok,
                    )
                else:
                    cur.executemany(
                        """
                        UPDATE player_profile
                        SET name=%s, name_lc=%s
                        WHERE run_id=%s AND uuid=%s
                        """,
                        updates_ok,
                    )

            if updates_fail and has_checked:
                cur.executemany(
                    """
                    UPDATE player_profile
                    SET name_checked_at=NOW()
                    WHERE run_id=%s AND uuid=%s
                    """,
                    [(run_id, u_bin) for (run_id, u_bin) in updates_fail],
                )

        conn.commit()

        print(
            f"resolve_names: run_id={run_id} candidates={total} resolved={resolved} failed={failed} "
            f"refresh_days={int(args.refresh_days)}"
        )
        return 0

    finally:
        try:
            if lock_acquired:
                release_db_lock(conn, args.lock_name)
        finally:
            conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
