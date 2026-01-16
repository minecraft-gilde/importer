# minecraft-gilde-importer

Importer/ETL für **Minecraft Gilde** (Minecraft-Gilde.de): liest Vanilla-Stats aus dem Minecraft-World-Ordner und schreibt daraus **materialisierte Leaderboards** + **Spieler-Stats** in eine MariaDB/MySQL.

Im Repo sind drei Dateien:

- `run_import.sh` – Wrapper/Runner: konfiguriert Pfade + DB-Env und führt Import + Name-Resolver aus
- `importer_streaming.py` – Hauptimport (Stats -> DB)
- `resolve_names.py` – Hintergrundjob: Spielernamen via Mojang-Endpoints nachpflegen/aktualisieren

---

## Was wird importiert?

- `world/stats/<uuid>.json` (Vanilla Stats pro Spieler)
- `usercache.json` (Primäre Quelle für UUID -> Name)

Filter:
- Es werden nur Spieler übernommen, deren `minecraft:custom.minecraft:play_time` >= `MIN_PLAY_TICKS` ist (Default: `72000` = ca. 1h).
- Optional können UUIDs ausgeschlossen werden.

---

## Was wird in die DB geschrieben?

Der Importer schreibt/aktualisiert (in-place) u. a.:

- `player_profile` – Anzeige/Suche (`name`, `name_lc`, optional `name_source`, `name_checked_at`, `last_seen`)
- `player_stats` – gesamtes Stats-JSON pro Spieler, **gzip-komprimiert** (`stats_gzip`) + SHA1 für Change-Detection (`stats_sha1`)
- `metric_value` – materialisierte Werte pro Metrik fuer Leaderboards
- `site_state` / `import_run` – Verwaltung eines aktiven `run_id` (in-place)
- optional `metric_award` – Top3 pro Metrik

Spezialfall:
- **Server-König**: pro aktivierter Metrik werden Top1/2/3 Punkte vergeben (Default `5,3,1`) und als eigene Metrik `king` in `metric_value` gespeichert.

---

## Eigenschaften

- **Streaming/Batched Writes**: keine Voll-Ladung aller Spieler in RAM
- **Hash-skip**: unveränderte Spieler werden nicht neu geschrieben und ihre Metriken nicht neu berechnet
- **Cleanup**: Spieler, die nicht mehr im Stats-Ordner sind (oder unter Threshold fallen), werden aus `player_profile`, `player_stats`, `metric_value` entfernt
- **DB Advisory Lock** (MariaDB `GET_LOCK`): verhindert parallele Imports
- **Name-Priorität** beim Import:
  1) `usercache.json`
  2) letzter Name aus DB (falls `usercache` unvollständig)
  3) stabiler Fallback (UUID-Hash)
- **Name-Resolver** kann Namen nachpflegen und periodisch refreshen (rate-limited)

---

## Voraussetzungen

- Python **3.9+**
- MariaDB **10.11+** (oder kompatibles MySQL)
- Python Dependency: `pymysql`
- Dateizugriff auf den Minecraft-Ordner (`world/stats`) und `usercache.json`
- Outbound HTTPS (nur für `resolve_names.py`, Mojang Endpoints)

---

## Installation

```bash
apt update
apt upgrade
apt install python3-pymysql
```

---

## Konfiguration

### DB-Credentials (env)
Beide Python-Skripte lesen die DB-Zugangsdaten bevorzugt aus Umgebungsvariablen:

- `STATS_DB_HOST`
- `STATS_DB_PORT` (Default `3306`)
- `STATS_DB_NAME`
- `STATS_DB_USER`
- `STATS_DB_PASS`

Alternativ können sie auch per CLI übergeben werden (`--db-host`, `--db-name`, ...).

### Runner `run_import.sh`
Im Script stellt man ein:

- `STATS_DIR` – Pfad zu `world/stats`
- `USERCACHE_JSON` – Pfad zu `usercache.json`
- `LOG_FILE` – Log-Datei (Importer schreibt aktiv dorthin, Resolver appended)
- `MIN_PLAY_TICKS` – Mindestspielzeit in Ticks
- `EXCLUDES` – Liste ausgeschlossener UUIDs
- Resolver-Tuning: `NAME_REFRESH_DAYS`, `NAME_MAX_PER_RUN`, `NAME_SLEEP_MS`


---

## Ausführen

### Empfohlen: alles über `run_import.sh`

```bash
chmod +x run_import.sh
./run_import.sh
```

Der Runner macht:
1) Import stats -> DB
2) Name-Backfill/Refresh (Output wird ans gleiche Log angehängt)

### Direkt: Importer

```bash
python3 importer_streaming.py \
  --stats-dir /pfad/zur/world/stats \
  --usercache /pfad/zur/usercache.json \
  --min-play-ticks 72000 \
  --log-file /var/log/minecraft/statistik/importer.log
```

Nützliche Optionen:
- `--exclude-uuid <uuid>` (repeatable)
- `--dry-run` (parsen + rechnen, aber nichts in DB schreiben)
- `--lock-name mc_stats_import` / `--lock-timeout 5`
- `--no-king` oder `--king-points 5,3,1`
- Flush-Tuning: `--flush-seen`, `--flush-profiles`, `--flush-changed`

### Direkt: Name Resolver

```bash
python3 resolve_names.py \
  --refresh-days 30 \
  --max-per-run 1500 \
  --sleep-ms 150
```

Hinweise:
- Default `run_id` ist `site_state.active_run_id`.
- Nutzt Mojang Sessionserver + Name-History Endpoint.
- Setzt (falls Spalten existieren) `name_source='mojang'` und `name_checked_at=NOW()`.

---

## Scheduling (cron Beispiel)

Alle 10 Minuten (Importer + Resolver), Log via Script:

```cron
*/10 * * * * /opt/minecraft-gilde-importer/run_import.sh
```

---

## Datenbank-Erwartungen (Schema-Interface)

Der Importer erwartet (mindestens) folgende Tabellen/Views:

- `site_state` (id=1, `active_run_id`)
- `import_run` (`id`, `generated_at`, `status`)
- `metric_def` (u. a. `id`, `enabled`, `sort_order` ...)
- `metric_source` (`metric_id`, `section`, `mc_key`, `weight`)
- `player_profile` (`run_id`, `uuid`, `name`, `name_lc`, `last_seen`, optional `name_source`, `name_checked_at`)
- `player_stats` (`run_id`, `uuid`, `stats_gzip`, `stats_sha1`, `updated_at`)
- `metric_value` (`run_id`, `metric_id`, `uuid`, `value`)
- optional `metric_award` (Top3 pro Metrik; wird nur genutzt, wenn die Tabelle existiert)

Wenn keine aktivierten Metriken existieren, bricht der Importer ab mit:
`No enabled metrics found. Did you seed metric_def + metric_source?`

---

## Troubleshooting

- **"DB config missing"**: Env `STATS_DB_HOST/USER/NAME` (und ggf. PASS) setzen oder CLI-Args nutzen.
- **"Could not acquire DB lock"**: Ein anderer Import läuft bereits. Timeout erhöhen oder Frequenz senken.
- **"stats-dir not found"**: Pfad zu `world/stats` prüfen.
- **Namen bleiben Fallback**: `resolve_names.py` benötigt outbound HTTPS; ggf. Rate-Limit hoch/runter drehen (`--sleep-ms`).

