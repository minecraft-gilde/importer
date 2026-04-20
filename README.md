# Stats Importer Plugin (Paper/Folia)

Dieses Repository enthält jetzt ein Java-Plugin, das den bisherigen Python-Importer für Minecraft-Stats auf Paper/Folia ersetzt.

## Altbestand

Die bisherigen Python-Dateien und DB-Skripte liegen unverändert unter:

- `temp/legacy-python/`

Darin kannst du jederzeit die alte Logik nachschlagen.

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
- `src/main/resources/metric-seeds.yml` (Seeds fuer `metric_def` und `metric_source`)

Wichtig:

- `import.interval-seconds`: Auto-Import-Intervall in Sekunden
- `import.stats-dir`: `auto` nutzt den Standardpfad des Servers (`<world>/stats`)
- `import.usercache-path`: `auto` nutzt `<server-root>/usercache.json`
- `import.worker-threads`: Anzahl paralleler Threads für die Stat-Berechnung
- `import.max-inflight-calculations`: Begrenzung für gleichzeitig ausstehende Berechnungen
- `database.*`: MariaDB-Zugangsdaten
- `bootstrap.*`: Schema-Check/Create und Seed-Import beim Plugin-Start

## DB-Bootstrap beim Start

Das Plugin kann beim Start automatisch:

1. Schema validieren
2. Fehlende Tabellen/Views anlegen (`db/schema.sql`)
3. Seeds aus `metric-seeds.yml` importieren (Upsert)

Das Verhalten steuerst du in `bootstrap.*` in der `config.yml`.

## Commands

- `/statsimport run`
- `/statsimport run ignorehash`
- `/statsimport status`
- `/statsimport reload`

## Datenbank

Das Plugin erwartet weiterhin das bestehende MariaDB-Schema (inkl. `metric_def`, `metric_source`, `site_state`, `import_run`, `player_profile`, `player_stats`, `metric_value` und optional `metric_award`).

