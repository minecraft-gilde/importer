﻿# Konfiguration

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
| `import.interval-seconds` | `14400` | Intervall des Timer-Imports in Sekunden (Minimum 1; 14400 = 4 Stunden) |
| `import.ignore-hash-on-timer` | `false` | Ignoriert SHA1-Hashprüfung bei Timerläufen |
| `import.retention.keep-runs` | `1` | Anzahl aufzubewahrender veröffentlichter Import-Snapshots (`1` = nur aktueller Run, `0` = keine automatische Löschung) |

## Inputpfade

| Key | Default | Bedeutung |
|---|---:|---|
| `import.stats-dir` | `auto` | Pfad zu `world/stats`; `auto/default/standard/leer` wird automatisch aufgelöst |
| `import.usercache-path` | `auto` | Pfad zu `usercache.json`; bei `auto` wird `<worldContainer>/usercache.json` genutzt |
| `import.banned-players-path` | `auto` | Pfad zu `banned-players.json`; bei `auto` wird `<worldContainer>/banned-players.json` genutzt |

## Filter und Ausschlüsse

| Key | Default | Bedeutung |
|---|---:|---|
| `import.min-play-ticks` | `72000` | Mindestspielzeit für Import (Ticks) |
| `import.safety.min-processed-files` | `1` | Bricht vor Snapshot-Veröffentlichung ab, wenn weniger Stats-Dateien gefunden wurden (`0` = deaktiviert) |
| `import.safety.min-kept-players` | `1` | Bricht vor Snapshot-Veröffentlichung ab, wenn weniger Spieler nach Filter übernommen wurden (`0` = deaktiviert) |
| `import.safety.max-parse-errors` | `0` | Bricht vor Snapshot-Veröffentlichung ab, wenn mehr Stats-JSONs nicht lesbar sind (`0` = keine tolerieren, `-1` = deaktiviert) |
| `import.exclude-uuids` | `[]` | UUID-Liste, die komplett ignoriert wird |

Hinweis: UUIDs werden flexibel geparst (mit oder ohne Bindestriche, case-insensitive). Ungültige Einträge werden ignoriert.

## Performance und Batching

| Key | Default | Bedeutung |
|---|---:|---|
| `import.worker-threads` | `6` | Anzahl Berechnungs-Threads für Metriken |
| `import.max-inflight-calculations` | `3000` | Maximal gleichzeitig ausstehende Berechnungen |
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

## Namensauflösung (Mojang)

| Key | Default | Bedeutung |
|---|---:|---|
| `import.name-resolver.enabled` | `true` | Aktiviert den Mojang-Resolver global |
| `import.name-resolver.max-per-run` | `1500` | Standard-Limit für manuelle Resolver-Läufe (`/statsimport resolve`) |
| `import.name-resolver.after-import-enabled` | `true` | Plant nach erfolgreicher Snapshot-Veröffentlichung einen Resolver-Lauf ein |
| `import.name-resolver.after-import-max-per-run` | `300` | Budget direkt nach Import (klein halten, um Importthread zu entlasten) |
| `import.name-resolver.maintenance-enabled` | `true` | Aktiviert separaten Hintergrund-Worker für Namenspflege |
| `import.name-resolver.maintenance-interval-seconds` | `14400` | Intervall des Hintergrund-Workers |
| `import.name-resolver.maintenance-max-per-run` | `500` | Budget pro Hintergrundlauf |
| `import.name-resolver.refresh-days` | `30` | Re-Check-Intervall in Tagen (`0` = nur fehlende/Fallback-Namen) |
| `import.name-resolver.sleep-ms` | `150` | Pause zwischen Requests (Rate-Limit-Schutz) |
| `import.name-resolver.connect-timeout-ms` | `5000` | Timeout für den Verbindungsaufbau |
| `import.name-resolver.request-timeout-ms` | `5000` | Timeout pro HTTP-Request |

Resolver-Reihenfolge:

1. `https://sessionserver.mojang.com/session/minecraft/profile/<uuid>`
2. `https://api.mojang.com/user/profiles/<uuid>/names` (letzter Name aus Verlauf)

Kandidaten-Priorität:

1. `fallback`/`unknown` und fehlende Namen
2. danach stale Einträge über `refresh-days`

Resolver-Läufe werden über eine gemeinsame Queue dedupliziert. After-Import, Maintenance und manuelle Resolver-Trigger laufen dadurch nie parallel; wenn mehrere Resolver-Trigger warten, wird das größte Budget übernommen.

## Abschnitt `database`

| Key | Default | Bedeutung |
|---|---:|---|
| `database.host` | `127.0.0.1` | DB-Host |
| `database.port` | `3306` | DB-Port |
| `database.name` | `mg-stats` | DB-Name |
| `database.user` | `stats_user` | DB-Benutzer |
| `database.password` | `change-me` | DB-Passwort |
| `database.pool-max-size` | `10` | Hikari Pool-Größe |
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
| `bootstrap.sync-seeds` | `true` | Seed-Datei bei jedem Start anwenden, entfernte `metric_source`-Einträge löschen und entfernte Metriken deaktivieren |
| `bootstrap.seed-file` | `metric-seeds.yml` | Seed-Datei (relativ zu Plugin-Datenordner oder absolut) |

## Validierung und Grenzwerte

Einige Werte werden beim Laden begrenzt:

- `interval-seconds >= 1`
- `min-play-ticks >= 0`
- `safety.max-parse-errors >= -1`
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
- `flush-profiles: 500`
- `flush-changed: 200`

## Mittlere Server

- `worker-threads: 4-6`
- `max-inflight-calculations: 1000-3000`
- `flush-profiles: 1000-2000`
- `flush-changed: 400-1000`

## Große Server

- `worker-threads: 8-12` (abh. von CPU)
- `max-inflight-calculations: 4000+`
- `flush-profiles: 2000-5000`
- `flush-changed: 1000-3000`

Wichtig: Erhöhe Werte schrittweise und beobachte DB-Latenzen, GC-Verhalten und Importdauer.

## Änderungswirkung pro Bereich

Diese Übersicht hilft bei der Einschätzung von Risiko und Beobachtungsbedarf.

| Bereich | Typische Keys | Wirkung | Nach Änderung prüfen |
|---|---|---|---|
| Laufsteuerung | `enabled`, `interval-seconds`, `ignore-hash-on-timer` | Häufigkeit und Rechenlast der Läufe | Importdauer, Überlappungen, Lock-Wartezeiten |
| Retention | `retention.keep-runs` | DB-Wachstum durch Snapshot-Historie | Anzahl `import_run`, DB-Größe, Rollback-Bedarf |
| Inputpfade | `stats-dir`, `usercache-path`, `banned-players-path` | Datenvollständigkeit und Namensqualität | geladene Spielerzahl, Name-Fallback-Anteil, Ban-Snapshot |
| Filter | `min-play-ticks`, `exclude-uuids` | Sichtbare Datenbasis | `kept`/`changed`, Spielerzahl in Views |
| Parallelität | `worker-threads`, `max-inflight-calculations` | CPU-/RAM-Last und Durchsatz | CPU, Heap, DB-Latenz, Laufzeit |
| Batching | `flush-profiles`, `flush-changed` | Write-Pattern und Transaktionsgröße | Roundtrips, Lock-Dauer, Fehlerquote |
| Locking | `db-lock-name`, `db-lock-timeout-seconds` | Verhalten bei Parallelzugriff | Lock-Fehler, Wartezeiten |
| King | `king-enabled`, `king-metric-id`, `king-points` | Leaderboard-Aggregation | Plausibilität `king`-Werte |
| Resolver | `name-resolver.*` | Namensqualität vs. Upstream-Last | resolved/failed/skipped, API-Rate-Limits |
| Datenbank | `database.*` | Verbindung, Pooling, Stabilität | Connection-Fehler, Timeout-Rate |
| Bootstrap | `bootstrap.*` | Schema-/Seed-Automation | Startlogs, Tabellen-/Seed-Vollständigkeit |

## Sichere Rollout-Reihenfolge für Konfigurationsänderungen

1. Änderung in kleiner Einheit vornehmen
2. Plugin reloaden oder neu starten
3. Manuellen Lauf starten: `/statsimport run`
4. Status und Laufkennzahlen prüfen: `/statsimport status`
5. SQL-Stichprobe auf `v_player_profile` und `v_metric_value`
6. Erst danach weitere Regler anpassen

## Minimal-Check nach jeder Konfigurationsänderung

- Lauf startet ohne Fehler
- `success=true` im letzten Lauf
- Spieler- und Metrikanzahlen plausibel
- Keine Lock-Stauung durch Timer/Parallelbetrieb
- Falls Resolver aktiv: Kandidaten werden abgearbeitet und nicht nur akkumuliert
