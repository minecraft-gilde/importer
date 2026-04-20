# Konfiguration

## Speicherort

Zur Laufzeit liegt die Konfiguration hier:

- `plugins/StatsImporter/config.yml`

Default-Werte im Repository:

- `src/main/resources/config.yml`

## Abschnitt `import`

## Steuerung und Timing

| Key | Default | Bedeutung |
|---|---:|---|
| `import.enabled` | `true` | Aktiviert periodischen Timer-Import beim Pluginstart |
| `import.interval-seconds` | `60` | Intervall des Timer-Imports in Sekunden (Minimum 1) |
| `import.ignore-hash-on-timer` | `false` | Ignoriert SHA1-Hashprüfung bei Timerläufen |

## Inputpfade

| Key | Default | Bedeutung |
|---|---:|---|
| `import.stats-dir` | `auto` | Pfad zu `world/stats`; `auto/default/standard/leer` wird automatisch aufgelöst |
| `import.usercache-path` | `auto` | Pfad zu `usercache.json`; bei `auto` wird `<worldContainer>/usercache.json` genutzt |

## Filter und Ausschlüsse

| Key | Default | Bedeutung |
|---|---:|---|
| `import.min-play-ticks` | `72000` | Mindestspielzeit für Import (Ticks) |
| `import.exclude-uuids` | `[]` | UUID-Liste, die komplett ignoriert wird |

Hinweis: UUIDs werden flexibel geparst (mit oder ohne Bindestriche, case-insensitive). Ungültige Einträge werden ignoriert.

## Performance und Batching

| Key | Default | Bedeutung |
|---|---:|---|
| `import.worker-threads` | `6` | Anzahl Berechnungs-Threads für Metriken |
| `import.max-inflight-calculations` | `3000` | Maximal gleichzeitig ausstehende Berechnungen |
| `import.flush-seen` | `2000` | Batch-Größe für `tmp_seen` |
| `import.flush-profiles` | `2000` | Batch-Größe für `player_profile` |
| `import.flush-changed` | `800` | Batch-Größe für geänderte Spieler (`player_stats` + `metric_value`) |

## DB-Lock

| Key | Default | Bedeutung |
|---|---:|---|
| `import.db-lock-name` | `mc_stats_import` | Name für MariaDB Advisory Lock |
| `import.db-lock-timeout-seconds` | `5` | Wartezeit für Lock-Erwerb |

## Server-König

| Key | Default | Bedeutung |
|---|---:|---|
| `import.king-enabled` | `true` | Aktiviert Top-3 Punkteberechnung je Metrik |
| `import.king-metric-id` | `king` | Ziel-Metrik-ID für Summenpunkte |
| `import.king-points` | `[5, 3, 1]` | Punkte für Platz 1/2/3 |

Hinweis: Die Liste wird intern auf mindestens 3 Werte normalisiert. Fehlende Werte werden mit `0` aufgefüllt.

## Abschnitt `database`

| Key | Default | Bedeutung |
|---|---:|---|
| `database.host` | `127.0.0.1` | DB-Host |
| `database.port` | `3306` | DB-Port |
| `database.name` | `mg-stats` | DB-Name |
| `database.user` | `stats_user` | DB-Benutzer |
| `database.password` | `change-me` | DB-Passwort |
| `database.pool-max-size` | `10` | Hikari PoolGröße |
| `database.connection-timeout-ms` | `10000` | Timeout für DB-Verbindungen |

Pflichtfelder:

- `database.host`
- `database.name`
- `database.user`

Fehlen diese Werte, bricht der Pluginstart mit Konfigurationsfehler ab.

## Abschnitt `bootstrap`

| Key | Default | Bedeutung |
|---|---:|---|
| `bootstrap.auto-schema` | `true` | Schema bei fehlenden DB-Objekten automatisch anwenden |
| `bootstrap.verify-schema` | `true` | Schema auf Pflichtobjekte prüfen |
| `bootstrap.seed-on-missing-schema` | `true` | Nach Schema-Erstellung Seed-Import starten |
| `bootstrap.seed-if-metric-def-empty` | `true` | Seed-Import starten, wenn `metric_def` leer ist |
| `bootstrap.seed-file` | `metric-seeds.yml` | Seed-Datei (relativ zu Plugin-Datenordner oder absolut) |

## Validierung und Grenzwerte

Einige Werte werden beim Laden begrenzt:

- `interval-seconds >= 1`
- `min-play-ticks >= 0`
- `worker-threads >= 1`
- `max-inflight-calculations >= 10`
- `flush-* >= 1`
- `db-lock-timeout-seconds >= 0`
- `database.port >= 1`
- `pool-max-size >= 1`
- `connection-timeout-ms >= 1000`

## Beispielprofile

## Kleine Server

- `worker-threads: 2`
- `max-inflight-calculations: 400`
- `flush-seen: 500`
- `flush-profiles: 500`
- `flush-changed: 200`

## Mittlere Server

- `worker-threads: 4-6`
- `max-inflight-calculations: 1000-3000`
- `flush-seen: 1000-2000`
- `flush-profiles: 1000-2000`
- `flush-changed: 400-1000`

## Grosse Server

- `worker-threads: 8-12` (abh. von CPU)
- `max-inflight-calculations: 4000+`
- `flush-seen/profiles: 2000-5000`
- `flush-changed: 1000-3000`

Wichtig: Erhöhe Werte schrittweise und beobachte DB-Latenzen, GC-Verhalten und Importdauer.


