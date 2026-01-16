#!/usr/bin/env bash
set -euo pipefail

# ====== CONFIG ======
# Minecraft stats dir (world/stats)
STATS_DIR="/home/minecraft/multicraft/servers/server3/world/stats"
# Minecraft usercache.json (used as the primary name source)
USERCACHE_JSON="/home/minecraft/multicraft/servers/server3/usercache.json"

# Log file used by the importer; resolver output is appended to the same file
LOG_FILE="/var/log/minecraft/statistik/importer.log"

# ====== DB CONFIG (exported so importer + resolver can reuse) ======
export STATS_DB_HOST=""
export STATS_DB_PORT="3306"
export STATS_DB_NAME="stats_v2"
export STATS_DB_USER=""
export STATS_DB_PASS=""

# ====== IMPORT SETTINGS ======
MIN_PLAY_TICKS="72000"   # e.g. 1h = 72000 ticks

# Optional: exclude UUID(s)
# EXCLUDES=( "00000000-0000-0000-0000-000000000000" )
EXCLUDES=()

# ====== NAME RESOLVER SETTINGS ======
NAME_REFRESH_DAYS="30"
NAME_MAX_PER_RUN="1500"
NAME_SLEEP_MS="150"

# ====== RUN ======
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$(dirname "$LOG_FILE")"

IMPORTER_ARGS=(
  "--stats-dir" "$STATS_DIR"
  "--usercache" "$USERCACHE_JSON"
  "--min-play-ticks" "$MIN_PLAY_TICKS"
  "--log-file" "$LOG_FILE"
)
for u in "${EXCLUDES[@]}"; do
  IMPORTER_ARGS+=( "--exclude-uuid" "$u" )
done

# 1) Import stats -> DB (in-place, hash-skip, batched)
python3 "$SCRIPT_DIR/importer_streaming.py" "${IMPORTER_ARGS[@]}"

# 2) Backfill + refresh player names (bounded + rate limited)
#    Output appended to the importer log for convenience.
python3 "$SCRIPT_DIR/resolve_names.py" \
  --refresh-days "$NAME_REFRESH_DAYS" \
  --max-per-run "$NAME_MAX_PER_RUN" \
  --sleep-ms "$NAME_SLEEP_MS" >> "$LOG_FILE" 2>&1
