# Stats Importer Plugin (Paper/Folia)

[![CI](https://img.shields.io/github/actions/workflow/status/minecraft-gilde/importer/ci.yml?branch=main&label=build)](https://github.com/minecraft-gilde/importer/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/minecraft-gilde/importer?label=release&cacheSeconds=300)](https://github.com/minecraft-gilde/importer/releases)
[![License](https://img.shields.io/github/license/minecraft-gilde/importer)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Paper](https://img.shields.io/badge/Paper-26.1-blue)
![Folia](https://img.shields.io/badge/Folia-supported-brightgreen)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white)](https://discord.minecraft-gilde.de)

Dieses Repository enthält ein Java-Plugin für den Import von Minecraft-Stats auf Paper/Folia.

## Voraussetzungen

- Java 25 (Build und Runtime)
- Paper/Folia 26.1

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

- `build/libs/stats-importer-plugin-<version>.jar`

## Konfiguration

Die gesamte Laufzeitkonfiguration liegt in:

- `src/main/resources/config.yml`
- `src/main/resources/metric-seeds.yml` (Seeds für `metric_def` und `metric_source`)

Wichtig:

- `import.interval-seconds`: Auto-Import-Intervall in Sekunden
- `import.safety.*`: Mindestwerte gegen falsche/leere Stats-Pfade
- `import.safety.max-parse-errors`: Grenze für kaputte Stats-JSONs vor Snapshot-Veröffentlichung
- `import.retention.keep-runs`: Aufbewahrung alter Snapshot-Runs
- `import.stats-dir`: `auto` nutzt den Paper/Folia-26.1-Standardpfad (`<world>/players/stats`)
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

