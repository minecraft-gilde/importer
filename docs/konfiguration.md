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
| `import.ignore-hash-on-timer` | `false` | Ignoriert SHA1-Hashpruefung bei Timerlaeufen |

## Inputpfade

| Key | Default | Bedeutung |
|---|---:|---|
| `import.stats-dir` | `auto` | Pfad zu `world/stats`; `auto/default/standard/leer` wird automatisch aufgeloest |
| `import.usercache-path` | `auto` | Pfad zu `usercache.json`; bei `auto` wird `<worldContainer>/usercache.json` genutzt |
| `import.banned-players-path` | `auto` | Pfad zu `banned-players.json`; bei `auto` wird `<worldContainer>/banned-players.json` genutzt |

## Filter und Ausschluesse

| Key | Default | Bedeutung |
|---|---:|---|
| `import.min-play-ticks` | `72000` | Mindestspielzeit fuer Import (Ticks) |
| `import.exclude-uuids` | `[]` | UUID-Liste, die komplett ignoriert wird |

Hinweis: UUIDs werden flexibel geparst (mit oder ohne Bindestriche, case-insensitive). Ungueltige Eintraege werden ignoriert.

## Performance und Batching

| Key | Default | Bedeutung |
|---|---:|---|
| `import.worker-threads` | `6` | Anzahl Berechnungs-Threads fuer Metriken |
| `import.max-inflight-calculations` | `3000` | Maximal gleichzeitig ausstehende Berechnungen |
| `import.flush-seen` | `2000` | Batch-Groesse fuer `tmp_seen` |
| `import.flush-profiles` | `2000` | Batch-Groesse fuer `player_profile` |
| `import.flush-changed` | `800` | Batch-Groesse fuer geaenderte Spieler (`player_stats` + `metric_value`) |

## DB-Lock

| Key | Default | Bedeutung |
|---|---:|---|
| `import.db-lock-name` | `mc_stats_import` | Name fuer MariaDB Advisory Lock |
| `import.db-lock-timeout-seconds` | `5` | Wartezeit fuer Lock-Erwerb |

## Server-Koenig

| Key | Default | Bedeutung |
|---|---:|---|
| `import.king-enabled` | `true` | Aktiviert Top-3 Punkteberechnung je Metrik |
| `import.king-metric-id` | `king` | Ziel-Metrik-ID fuer Summenpunkte |
| `import.king-points` | `[5, 3, 1]` | Punkte fuer Platz 1/2/3 |

Hinweis: Die Liste wird intern auf mindestens 3 Werte normalisiert. Fehlende Werte werden mit `0` aufgefuellt.

## Namensaufloesung (Mojang)

| Key | Default | Bedeutung |
|---|---:|---|
| `import.name-resolver.enabled` | `true` | Aktiviert den Mojang-Resolver global |
| `import.name-resolver.max-per-run` | `1500` | Standard-Limit fuer manuelle Resolver-Laeufe (`/statsimport resolve`) |
| `import.name-resolver.after-import-enabled` | `true` | Aktiviert Resolver-Lauf direkt nach Import |
| `import.name-resolver.after-import-max-per-run` | `300` | Budget direkt nach Import (klein halten, um Importthread zu entlasten) |
| `import.name-resolver.maintenance-enabled` | `true` | Aktiviert separaten Hintergrund-Worker fuer Namenspflege |
| `import.name-resolver.maintenance-interval-seconds` | `300` | Intervall des Hintergrund-Workers |
| `import.name-resolver.maintenance-max-per-run` | `500` | Budget pro Hintergrundlauf |
| `import.name-resolver.refresh-days` | `30` | Re-Check-Intervall in Tagen (`0` = nur fehlende/Fallback-Namen) |
| `import.name-resolver.sleep-ms` | `150` | Pause zwischen Requests (Rate-Limit-Schutz) |
| `import.name-resolver.connect-timeout-ms` | `5000` | Timeout fuer den Verbindungsaufbau |
| `import.name-resolver.request-timeout-ms` | `5000` | Timeout pro HTTP-Request |

Resolver-Reihenfolge:

1. `https://sessionserver.mojang.com/session/minecraft/profile/<uuid>`
2. `https://api.mojang.com/user/profiles/<uuid>/names` (letzter Name aus Verlauf)

Kandidaten-Prioritaet:

1. `fallback`/`unknown` und fehlende Namen
2. danach stale Eintraege ueber `refresh-days`

## Abschnitt `database`

| Key | Default | Bedeutung |
|---|---:|---|
| `database.host` | `127.0.0.1` | DB-Host |
| `database.port` | `3306` | DB-Port |
| `database.name` | `mg-stats` | DB-Name |
| `database.user` | `stats_user` | DB-Benutzer |
| `database.password` | `change-me` | DB-Passwort |
| `database.pool-max-size` | `10` | Hikari Pool-Groesse |
| `database.connection-timeout-ms` | `10000` | Timeout fuer DB-Verbindungen |

Pflichtfelder:

- `database.host`
- `database.name`
- `database.user`

Fehlen diese Werte, bricht der Pluginstart mit Konfigurationsfehler ab.

## Abschnitt `bootstrap`

| Key | Default | Bedeutung |
|---|---:|---|
| `bootstrap.auto-schema` | `true` | Schema bei fehlenden DB-Objekten automatisch anwenden |
| `bootstrap.verify-schema` | `true` | Schema auf Pflichtobjekte pruefen |
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
- `name-resolver.max-per-run >= 1`
- `name-resolver.after-import-max-per-run >= 1`
- `name-resolver.maintenance-interval-seconds >= 1`
- `name-resolver.maintenance-max-per-run >= 1`
- `name-resolver.refresh-days >= 0`
- `name-resolver.sleep-ms >= 0`
- `name-resolver.connect-timeout-ms >= 1000`
- `name-resolver.request-timeout-ms >= 1000`
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

Wichtig: Erhoehe Werte schrittweise und beobachte DB-Latenzen, GC-Verhalten und Importdauer.
