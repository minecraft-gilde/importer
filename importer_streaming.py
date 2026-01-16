#!/usr/bin/env python3
"""
Minecraft -> MariaDB importer (JSON-per-player + materialized leaderboards)

Writes:
- player_profile (search/display)
- player_stats  (gzip-compressed JSON of *all* stats per player + sha1 for change detection)
- metric_value  (materialized values for leaderboards, computed from metric_source)
- (optional) metric_award (top3 per metric; only if table exists)

Designed for:
- MariaDB 10.11+
- remote DB
- local_infile = OFF
- in-place updates (single active run_id)

Key scalability properties:
- Streams/batches writes (does NOT keep all rows in RAM)
- Hash-skip: unchanged players are not rewritten and their metrics are not recomputed
- Cleanup: players no longer present are removed using a temp table

Usage example:
  python3 importer.py \
    --stats-dir /path/to/world/stats \
    --usercache /path/to/usercache.json \
    --db-host db.example.com --db-name mydb --db-user u --db-pass p \
    --log-file /var/log/mc-stats/import.log
"""

from __future__ import annotations

import argparse
import dataclasses
import gzip
import hashlib
import json
import logging
import os
from pathlib import Path
import sys
import time
import uuid as uuidlib
from typing import Dict, Iterable, List, Optional, Tuple

import pymysql  # type: ignore
import pymysql.cursors  # type: ignore


@dataclasses.dataclass(frozen=True)
class MetricSource:
    metric_id: str
    section: str
    mc_key: str
    weight: int


def uuid_to_bin(u: str) -> bytes:
    # Accept UUID with or without dashes
    u = u.strip()
    return uuidlib.UUID(u).bytes


def bin_to_uuid(b: bytes) -> str:
    return str(uuidlib.UUID(bytes=b))


def iter_stats_files(stats_dir: Path) -> Iterable[Tuple[str, Path]]:
    # Vanilla: world/stats/<uuid>.json
    for p in stats_dir.glob("*.json"):
        yield p.stem, p


def strip_wall_banners(stats: dict) -> None:
    # Stats structure: {section: {key: value}}
    for sec, kv in list(stats.items()):
        if not isinstance(kv, dict):
            continue
        for k in list(kv.keys()):
            if isinstance(k, str) and k.endswith("_wall_banner"):
                kv.pop(k, None)


def load_usercache(usercache_path: Path) -> Dict[bytes, str]:
    """
    usercache.json is typically an array like:
      [{"name":"Player","uuid":"...","expiresOn":"..."}]
    """
    if not usercache_path.is_file():
        return {}
    data = json.loads(usercache_path.read_text(encoding="utf-8", errors="replace"))
    out: Dict[bytes, str] = {}
    if isinstance(data, list):
        for it in data:
            if not isinstance(it, dict):
                continue
            name = it.get("name")
            u = it.get("uuid")
            if not isinstance(name, str) or not name:
                continue
            if not isinstance(u, str) or not u:
                continue
            try:
                out[uuid_to_bin(u)] = name[:16]
            except Exception:
                continue
    return out


def connect_db(args: argparse.Namespace):
    host = args.db_host or os.environ.get("STATS_DB_HOST", "")
    user = args.db_user or os.environ.get("STATS_DB_USER", "")
    password = args.db_pass or os.environ.get("STATS_DB_PASS", "")
    db = args.db_name or os.environ.get("STATS_DB_NAME", "")
    port = int(args.db_port or os.environ.get("STATS_DB_PORT", "3306"))

    if not host or not user or not db:
        raise SystemExit("DB config missing. Set env STATS_DB_HOST/USER/NAME (and PASS) or pass CLI args.")

    conn_kwargs = dict(
        host=host,
        port=port,
        user=user,
        password=password,
        database=db,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.Cursor,
    )

    # Nice-to-have for remote DB, but some distro PyMySQL builds don't support it.
    try:
        return pymysql.connect(**conn_kwargs, compress=True)
    except NotImplementedError as e:
        if "compress" in str(e):
            return pymysql.connect(**conn_kwargs)
        raise


def ensure_run_id(conn) -> int:
    """
    In-place mode:
    - if site_state.active_run_id is NULL: create one run and set it active.
    - else: reuse existing active_run_id.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT active_run_id FROM site_state WHERE id=1")
        row = cur.fetchone()
        active = row[0] if row else None

        if active is None:
            cur.execute("INSERT INTO import_run (generated_at, status) VALUES (NOW(), 'active')")
            run_id = cur.lastrowid
            cur.execute("UPDATE site_state SET active_run_id=%s WHERE id=1", (run_id,))
            conn.commit()
            return int(run_id)

        # Touch the run (best effort)
        cur.execute("UPDATE import_run SET generated_at=NOW(), status='active' WHERE id=%s", (active,))
        conn.commit()
        return int(active)


def load_metric_sources(conn) -> Dict[str, List[MetricSource]]:
    """
    Returns metric_id -> list of sources (enabled metrics only).
    """
    sql = """
      SELECT ms.metric_id, ms.section, ms.mc_key, ms.weight
      FROM metric_source ms
      JOIN metric_def md ON md.id = ms.metric_id
      WHERE md.enabled = 1
      ORDER BY md.sort_order ASC, ms.metric_id ASC
    """
    out: Dict[str, List[MetricSource]] = {}
    with conn.cursor() as cur:
        cur.execute(sql)
        for metric_id, section, mc_key, weight in cur.fetchall():
            out.setdefault(str(metric_id), []).append(
                MetricSource(metric_id=str(metric_id), section=str(section), mc_key=str(mc_key), weight=int(weight))
            )
    return out


def load_existing_hashes(conn, run_id: int) -> Dict[bytes, bytes]:
    out: Dict[bytes, bytes] = {}
    with conn.cursor() as cur:
        cur.execute("SELECT uuid, stats_sha1 FROM player_stats WHERE run_id=%s", (run_id,))
        for u_bin, sha in cur.fetchall():
            if isinstance(u_bin, (bytes, bytearray)) and isinstance(sha, (bytes, bytearray)) and len(sha) == 20:
                out[bytes(u_bin)] = bytes(sha)
    return out


def load_existing_profile_meta(conn, run_id: int, has_source: bool, has_checked: bool):
    """Load last known name (+ optional meta) from DB so we don't overwrite them with UUID fallbacks.

    Returns: uuid_bin -> (name, name_source, name_checked_at_or_None)
    """
    out = {}
    cols = ['uuid', 'name']
    if has_source:
        cols.append('name_source')
    if has_checked:
        cols.append('name_checked_at')

    sql = f"SELECT {', '.join(cols)} FROM player_profile WHERE run_id=%s"
    with conn.cursor() as cur:
        cur.execute(sql, (run_id,))
        for row in cur.fetchall():
            if not row:
                continue
            u_bin = row[0]
            if not isinstance(u_bin, (bytes, bytearray)):
                continue
            name = row[1]
            if not isinstance(name, str) or not name:
                continue
            name = name[:16]

            name_source = None
            name_checked = None
            if has_source:
                name_source = row[2] if len(row) > 2 else None
            if has_checked:
                name_checked = row[-1]
            out[bytes(u_bin)] = (name, str(name_source) if name_source is not None else None, name_checked)
    return out




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


def table_exists(conn, name: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = %s
            LIMIT 1
            """,
            (name,),
        )
        return cur.fetchone() is not None


def acquire_db_lock(conn, lock_name: str, timeout_s: int) -> bool:
    """Uses MariaDB GET_LOCK to prevent concurrent imports."""
    with conn.cursor() as cur:
        cur.execute("SELECT GET_LOCK(%s, %s)", (lock_name, int(timeout_s)))
        row = cur.fetchone()
        return bool(row and row[0] == 1)


def release_db_lock(conn, lock_name: str) -> None:
    with conn.cursor() as cur:
        cur.execute("DO RELEASE_LOCK(%s)", (lock_name,))
    conn.commit()


def ensure_king_metric(conn, king_metric_id: str = "king") -> None:
    """Ensure metric_def contains the special Server-König points metric."""
    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM metric_def WHERE id=%s", (king_metric_id,))
        if cur.fetchone():
            return
        cur.execute(
            """
            INSERT INTO metric_def (id, label, category, unit, divisor, decimals, sort_order, enabled)
            VALUES (%s, 'Server-König', 'Allgemein', 'Punkte', 1, 0, 0, 1)
            """,
            (king_metric_id,),
        )
    conn.commit()


def chunked(seq: List, n: int) -> Iterable[List]:
    for i in range(0, len(seq), n):
        yield seq[i : i + n]


def recompute_king_points(
    conn,
    run_id: int,
    metric_ids: List[str],
    king_metric_id: str = "king",
    award_points: Tuple[int, int, int] = (5, 3, 1),
) -> None:
    """Recompute Server-König points: top1=5, top2=3, top3=1 per metric."""
    ensure_king_metric(conn, king_metric_id)
    store_awards = table_exists(conn, "metric_award")

    points_map: Dict[bytes, int] = {}
    award_rows: List[Tuple[int, str, int, bytes, int, int]] = []

    sql_top3 = (
        "SELECT uuid, value FROM metric_value "
        "WHERE run_id=%s AND metric_id=%s AND value>0 "
        "ORDER BY value DESC, uuid ASC LIMIT 3"
    )

    with conn.cursor() as cur:
        cur.execute("DELETE FROM metric_value WHERE run_id=%s AND metric_id=%s", (run_id, king_metric_id))
        if store_awards:
            cur.execute("DELETE FROM metric_award WHERE run_id=%s", (run_id,))
    conn.commit()

    with conn.cursor() as cur:
        for mid in metric_ids:
            if mid == king_metric_id:
                continue
            cur.execute(sql_top3, (run_id, mid))
            rows = cur.fetchall()
            for i, (u_bin, val) in enumerate(rows):
                if i >= len(award_points):
                    break
                pts = int(award_points[i])
                if pts <= 0:
                    continue
                u_bin = bytes(u_bin)
                points_map[u_bin] = points_map.get(u_bin, 0) + pts
                if store_awards:
                    award_rows.append((run_id, str(mid), i + 1, u_bin, pts, int(val)))

    if store_awards and award_rows:
        sql_aw = (
            "INSERT INTO metric_award (run_id, metric_id, place, uuid, points, value) "
            "VALUES (%s,%s,%s,%s,%s,%s)"
        )
        with conn.cursor() as cur:
            for chunk in chunked(award_rows, 2000):
                cur.executemany(sql_aw, chunk)
        conn.commit()

    if points_map:
        mv_rows = [(run_id, king_metric_id, u, p) for (u, p) in points_map.items() if p > 0]
        sql_mv = "INSERT INTO metric_value (run_id, metric_id, uuid, value) VALUES (%s,%s,%s,%s)"
        with conn.cursor() as cur:
            for chunk in chunked(mv_rows, 2000):
                cur.executemany(sql_mv, chunk)
        conn.commit()


def flush_seen(conn, seen_buf: List[Tuple[bytes]], log: logging.Logger) -> None:
    if not seen_buf:
        return
    with conn.cursor() as cur:
        cur.executemany("INSERT IGNORE INTO tmp_seen (uuid) VALUES (%s)", seen_buf)
    conn.commit()
    log.debug("Flushed tmp_seen: %d", len(seen_buf))
    seen_buf.clear()


def flush_profiles(conn, run_id: int, profile_buf, log: logging.Logger, has_source: bool, has_checked: bool) -> None:
    if not profile_buf:
        return

    if has_source and has_checked:
        sql_profile = """
          INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, name_checked_at, last_seen)
          VALUES (%s,%s,%s,%s,%s,%s,%s)
          ON DUPLICATE KEY UPDATE
            name=VALUES(name),
            name_lc=VALUES(name_lc),
            name_source=VALUES(name_source),
            name_checked_at=VALUES(name_checked_at),
            last_seen=VALUES(last_seen)
        """
    elif has_source:
        sql_profile = """
          INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, last_seen)
          VALUES (%s,%s,%s,%s,%s,%s)
          ON DUPLICATE KEY UPDATE
            name=VALUES(name),
            name_lc=VALUES(name_lc),
            name_source=VALUES(name_source),
            last_seen=VALUES(last_seen)
        """
    else:
        sql_profile = """
          INSERT INTO player_profile (run_id, uuid, name, name_lc, last_seen)
          VALUES (%s,%s,%s,%s,%s)
          ON DUPLICATE KEY UPDATE
            name=VALUES(name),
            name_lc=VALUES(name_lc),
            last_seen=VALUES(last_seen)
        """

    with conn.cursor() as cur:
        cur.executemany(sql_profile, profile_buf)
    conn.commit()
    log.debug("Flushed player_profile: %d", len(profile_buf))
    profile_buf.clear()


def flush_changed_batch(
    conn,
    run_id: int,
    changed_uuids: List[bytes],
    stats_rows: List[Tuple[int, bytes, bytes, bytes]],
    metric_rows: List[Tuple[int, str, bytes, int]],
    log: logging.Logger,
) -> None:
    if not changed_uuids:
        return

    # Delete old metric rows for these uuids (so metrics that become 0 disappear)
    with conn.cursor() as cur:
        for chunk in chunked(changed_uuids, 1500):
            placeholders = ",".join(["%s"] * len(chunk))
            cur.execute(
                f"DELETE FROM metric_value WHERE run_id=%s AND uuid IN ({placeholders})",
                (run_id, *chunk),
            )
    conn.commit()

    # Upsert player_stats
    if stats_rows:
        sql_stats = """
          INSERT INTO player_stats (run_id, uuid, stats_gzip, stats_sha1, updated_at)
          VALUES (%s,%s,%s,%s,NOW())
          ON DUPLICATE KEY UPDATE
            stats_gzip=VALUES(stats_gzip),
            stats_sha1=VALUES(stats_sha1),
            updated_at=VALUES(updated_at)
        """
        with conn.cursor() as cur:
            for chunk in chunked(stats_rows, 500):
                cur.executemany(sql_stats, chunk)
        conn.commit()

    # Insert metric_value
    if metric_rows:
        sql_mv = """
          INSERT INTO metric_value (run_id, metric_id, uuid, value)
          VALUES (%s,%s,%s,%s)
          ON DUPLICATE KEY UPDATE value=VALUES(value)
        """
        with conn.cursor() as cur:
            for chunk in chunked(metric_rows, 2000):
                cur.executemany(sql_mv, chunk)
        conn.commit()

    log.debug(
        "Flushed changed batch: players=%d, stats_rows=%d, metric_rows=%d",
        len(changed_uuids),
        len(stats_rows),
        len(metric_rows),
    )

    changed_uuids.clear()
    stats_rows.clear()
    metric_rows.clear()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stats-dir", required=True, help="Path to world/stats directory (contains UUID.json files)")
    ap.add_argument("--usercache", required=True, help="Path to usercache.json")
    ap.add_argument("--min-play-ticks", type=int, default=72000, help="Filter: minecraft:play_time >= this")
    ap.add_argument("--exclude-uuid", action="append", default=[], help="UUID to exclude (repeatable)")

    ap.add_argument("--db-host")
    ap.add_argument("--db-port")
    ap.add_argument("--db-name")
    ap.add_argument("--db-user")
    ap.add_argument("--db-pass")

    ap.add_argument("--log-file", default="", help="Optional log file path")
    ap.add_argument("--dry-run", action="store_true", help="Parse and compute only, do not write to DB")

    # batching / scaling knobs
    ap.add_argument("--flush-seen", type=int, default=2000, help="Flush tmp_seen after N uuids")
    ap.add_argument("--flush-profiles", type=int, default=2000, help="Flush player_profile after N rows")
    ap.add_argument("--flush-changed", type=int, default=800, help="Flush changed-player batch after N players")

    # DB advisory lock (recommended when scheduled)
    ap.add_argument("--lock-name", default="mc_stats_import", help="DB lock name used with GET_LOCK")
    ap.add_argument("--lock-timeout", type=int, default=5, help="Seconds to wait for DB lock")

    # Server-König (points leaderboard)
    ap.add_argument("--king-metric-id", default="king", help="metric_def.id used for Server-König points")
    ap.add_argument("--king-points", default="5,3,1", help="Comma-separated points for ranks 1..N (default: 5,3,1)")
    ap.add_argument("--no-king", action="store_true", help="Disable Server-König point recomputation")

    args = ap.parse_args()

    handlers: List[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if args.log_file:
        Path(args.log_file).parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(args.log_file, encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s", handlers=handlers)
    log = logging.getLogger("importer")

    stats_dir = Path(args.stats_dir)
    usercache = Path(args.usercache)
    if not stats_dir.is_dir():
        log.error("stats-dir not found: %s", stats_dir)
        return 2

    excluded: set[bytes] = set()
    for u in args.exclude_uuid:
        try:
            excluded.add(uuid_to_bin(u))
        except Exception:
            log.warning("Invalid exclude uuid ignored: %s", u)

    name_map = load_usercache(usercache)
    log.info("Loaded %d names from usercache", len(name_map))

    conn = connect_db(args)
    lock_acquired = False
    try:
        lock_acquired = acquire_db_lock(conn, args.lock_name, args.lock_timeout)
        if not lock_acquired:
            log.error("Could not acquire DB lock '%s' within %ss (another import running?)", args.lock_name, args.lock_timeout)
            return 10

        run_id = ensure_run_id(conn)
        log.info("Using run_id=%d (in-place)", run_id)

        metric_sources = load_metric_sources(conn)
        if not metric_sources:
            log.error("No enabled metrics found. Did you seed metric_def + metric_source?")
            return 3
        log.info("Loaded %d metrics with sources", len(metric_sources))

        existing_hash = load_existing_hashes(conn, run_id)
        log.info("Loaded %d existing player hashes", len(existing_hash))
        has_source = table_has_column(conn, 'player_profile', 'name_source')
        has_checked = table_has_column(conn, 'player_profile', 'name_checked_at')

        existing_profiles = load_existing_profile_meta(conn, run_id, has_source, has_checked)
        log.info('Loaded %d existing player profiles (name/meta)', len(existing_profiles))

        now_ts = time.strftime("%Y-%m-%d %H:%M:%S")

        if args.dry_run:
            log.info("Dry-run enabled: will not write to DB.")
        else:
            with conn.cursor() as cur:
                cur.execute("DROP TEMPORARY TABLE IF EXISTS tmp_seen")
                cur.execute("CREATE TEMPORARY TABLE tmp_seen (uuid BINARY(16) PRIMARY KEY) ENGINE=InnoDB")
            conn.commit()

        processed = kept = changed = 0

        seen_buf: List[Tuple[bytes]] = []
        profile_buf: List[tuple] = []  # tuples depend on whether name_source/name_checked_at columns exist
        changed_uuids: List[bytes] = []
        stats_buf: List[Tuple[int, bytes, bytes, bytes]] = []
        metric_buf: List[Tuple[int, str, bytes, int]] = []

        def flush_all(force: bool = False) -> None:
            if args.dry_run:
                # nothing to flush
                seen_buf.clear()
                profile_buf.clear()
                changed_uuids.clear()
                stats_buf.clear()
                metric_buf.clear()
                return
            flush_seen(conn, seen_buf, log)
            flush_profiles(conn, run_id, profile_buf, log, has_source, has_checked)
            flush_changed_batch(conn, run_id, changed_uuids, stats_buf, metric_buf, log)

        for uuid_str, path in iter_stats_files(stats_dir):
            processed += 1
            try:
                u_bin = uuid_to_bin(uuid_str)
            except Exception:
                log.debug("Skip invalid filename UUID: %s", uuid_str)
                continue
            if u_bin in excluded:
                continue

            try:
                raw = json.loads(path.read_text(encoding="utf-8", errors="replace"))
            except Exception as e:
                log.warning("Cannot parse %s: %s", path.name, e)
                continue

            stats = raw.get("stats") if isinstance(raw, dict) else None
            if not isinstance(stats, dict):
                stats = raw if isinstance(raw, dict) else {}
            strip_wall_banners(stats)

            play_time = 0
            try:
                play_time = int(stats.get("minecraft:custom", {}).get("minecraft:play_time", 0))
            except Exception:
                play_time = 0
            if play_time < args.min_play_ticks:
                continue

            kept += 1

            if not args.dry_run:
                seen_buf.append((u_bin,))
                if len(seen_buf) >= int(args.flush_seen):
                    flush_seen(conn, seen_buf, log)

            # Name resolution priority:
            # 1) current Minecraft usercache.json
            # 2) last known name from DB (prevents clobbering names when usercache is incomplete)
            # 3) stable UUID fallback (should become rare once names are learned)
            uc_name = name_map.get(u_bin)
            if uc_name:
                name = uc_name
                name_source = "usercache"
                # mark as recently checked so the resolver doesn't immediately re-check it
                name_checked_at = now_ts if has_checked else None
            else:
                meta = existing_profiles.get(u_bin)
                if meta:
                    name = meta[0]
                    name_source = meta[1] or "unknown"
                    name_checked_at = meta[2] if has_checked else None
                else:
                    name = uuidlib.UUID(bytes=u_bin).hex[:12]
                    name_source = "fallback"
                    name_checked_at = None

            name_lc = name.lower()

            if not args.dry_run:
                if has_source and has_checked:
                    profile_buf.append((run_id, u_bin, name, name_lc, name_source, name_checked_at, now_ts))
                elif has_source:
                    profile_buf.append((run_id, u_bin, name, name_lc, name_source, now_ts))
                else:
                    profile_buf.append((run_id, u_bin, name, name_lc, now_ts))

                if len(profile_buf) >= int(args.flush_profiles):
                    flush_profiles(conn, run_id, profile_buf, log, has_source, has_checked)

            # canonical JSON for hash/storage
            json_bytes = json.dumps(stats, sort_keys=True, separators=(",", ":")).encode("utf-8")
            sha1 = hashlib.sha1(json_bytes).digest()

            if existing_hash.get(u_bin) == sha1:
                continue

            changed += 1
            existing_hash[u_bin] = sha1  # keep dict current during this run

            if args.dry_run:
                continue

            changed_uuids.append(u_bin)
            gz = gzip.compress(json_bytes, compresslevel=6)
            stats_buf.append((run_id, u_bin, gz, sha1))

            # compute metrics (only store >0)
            for metric_id, sources in metric_sources.items():
                total = 0
                sec_map = None  # tiny micro-opt
                for src in sources:
                    try:
                        sec_map = stats.get(src.section, {})
                        v = sec_map.get(src.mc_key, 0) if isinstance(sec_map, dict) else 0
                        if v is None:
                            continue
                        total += int(v) * int(src.weight)
                    except Exception:
                        continue
                if total > 0:
                    metric_buf.append((run_id, metric_id, u_bin, total))

            if len(changed_uuids) >= int(args.flush_changed):
                flush_changed_batch(conn, run_id, changed_uuids, stats_buf, metric_buf, log)

        log.info("Processed files=%d, kept=%d, changed=%d", processed, kept, changed)

        if args.dry_run:
            return 0

        # Flush remaining buffers
        flush_all(force=True)

        # Cleanup: remove players no longer present (or below playtime threshold)
        with conn.cursor() as cur:
            cur.execute(
                """
                DELETE p FROM player_profile p
                LEFT JOIN tmp_seen s ON p.uuid = s.uuid
                WHERE p.run_id=%s AND s.uuid IS NULL
                """,
                (run_id,),
            )
            cur.execute(
                """
                DELETE ps FROM player_stats ps
                LEFT JOIN tmp_seen s ON ps.uuid = s.uuid
                WHERE ps.run_id=%s AND s.uuid IS NULL
                """,
                (run_id,),
            )
            cur.execute(
                """
                DELETE mv FROM metric_value mv
                LEFT JOIN tmp_seen s ON mv.uuid = s.uuid
                WHERE mv.run_id=%s AND s.uuid IS NULL
                """,
                (run_id,),
            )
        conn.commit()

        # Server-König points
        if not args.no_king:
            try:
                pts = [int(x.strip()) for x in str(args.king_points).split(",") if x.strip()]
                if not pts:
                    pts = [5, 3, 1]
                while len(pts) < 3:
                    pts.append(0)
                recompute_king_points(
                    conn,
                    run_id=run_id,
                    metric_ids=list(metric_sources.keys()),
                    king_metric_id=str(args.king_metric_id),
                    award_points=(pts[0], pts[1], pts[2]),
                )
                log.info("Recomputed Server-König (%s) points using %s", args.king_metric_id, pts[:3])
            except Exception as e:
                log.warning("Server-König recompute failed (continuing): %s", e)

        # Touch import_run timestamp
        with conn.cursor() as cur:
            cur.execute("UPDATE import_run SET generated_at=NOW(), status='active' WHERE id=%s", (run_id,))
        conn.commit()

        log.info("Import OK. Updated generated_at for run_id=%d", run_id)
        return 0

    finally:
        try:
            if lock_acquired:
                release_db_lock(conn, args.lock_name)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
