# Stats Importer Plugin (Paper/Folia)

Dieses Repository enthält ein Java-Plugin für den Import von Minecraft-Stats auf Paper/Folia.

## Dokumentation

Die ausführliche Projektdokumentation liegt unter:

- [docs/README.md](docs/README.md)

## Build

```bash
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew.bat build
```

Ergebnis-JAR:

- `build/libs/stats-importer-plugin-1.0.0.jar`

## Konfiguration

Die gesamte Laufzeitkonfiguration liegt in:

- `src/main/resources/config.yml`
- `src/main/resources/metric-seeds.yml` (Seeds für `metric_def` und `metric_source`)

Wichtig:

- `import.interval-seconds`: Auto-Import-Intervall in Sekunden
- `import.safety.*`: Mindestwerte gegen falsche/leere Stats-Pfade
- `import.safety.max-parse-errors`: Grenze für kaputte Stats-JSONs vor Snapshot-Veröffentlichung
- `import.retention.keep-runs`: Aufbewahrung alter Snapshot-Runs
- `import.stats-dir`: `auto` nutzt den Standardpfad des Servers (`<world>/stats`)
- `import.usercache-path`: `auto` nutzt `<server-root>/usercache.json`
- `import.banned-players-path`: `auto` nutzt `<server-root>/banned-players.json`
- `import.worker-threads`: Anzahl paralleler Threads für die Stat-Berechnung
- `import.max-inflight-calculations`: Begrenzung für gleichzeitig ausstehende Berechnungen
- `database.*`: MariaDB-Zugangsdaten
- `bootstrap.*`: Schema-Check/Create und Seed-Import beim Plugin-Start
- `bootstrap.sync-seeds`: hält `metric_def`/`metric_source` am Seed-Sollzustand

## DB-Bootstrap beim Start

Das Plugin kann beim Start automatisch:

1. Schema validieren
2. Fehlende Tabellen/Views anlegen (`db/schema.sql`)
3. Seeds aus `metric-seeds.yml` importieren (Upsert)

Das Verhalten steuerst du in `bootstrap.*` in der `config.yml`.

## Commands

- `/statsimport run`
- `/statsimport run ignorehash`
- `/statsimport run dryrun`
- `/statsimport status`
- `/statsimport reload`
- `/statsimport resolve`
- `/statsimport resolve <max>`

## Datenbank

Das Plugin erwartet weiterhin das bestehende MariaDB-Schema (inkl. `metric_def`, `metric_source`, `site_state`, `import_run`, `player_profile`, `player_known`, `player_ban`, `player_stats`, `metric_value` und optional `metric_award`).

