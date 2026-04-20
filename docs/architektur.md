# Architektur

## Systemkontext

Das Plugin läuft innerhalb eines Paper/Folia Servers und übernimmt ETL-Aufgaben:

- Input: `world/stats/<uuid>.json` + `usercache.json`
- Verarbeitung: Filter, Normierung, Metrikberechnung, Hash-Vergleich
- Output: Tabellen `player_profile`, `player_stats`, `metric_value` (plus optional `metric_award`)

Die aktuelle Datenbasis wird über `site_state.active_run_id` verwaltet.

## Lifecycle des Plugins

Beim Start (`ImporterPlugin.onEnable`) passiert:

1. Default-Konfiguration sicherstellen (`config.yml`)
2. Seed-Datei bereitstellen (`metric-seeds.yml`)
3. Konfiguration laden und validieren (`ConfigLoader`)
4. Datenbankpool initialisieren (`DatabaseManager`, HikariCP)
5. DB-Bootstrap ausführen (`SchemaBootstrapper.ensureReady`)
6. Import-Subsystem aufbauen (`ImportCoordinator`, `ImportScheduler`)
7. Timer starten, falls `import.enabled=true`
8. Command `/statsimport` registrieren

Beim Stop (`onDisable`) werden Scheduler, Workerpools und DB-Pool sauber geschlossen.

## Komponenten und Verantwortungen

## Plugin Root

- `ImporterPlugin`
  - verdrahtet Runtime-Komponenten
  - steuert Start/Stop/Reload
  - blockiert Reload während laufendem Import

## Konfiguration

- `ConfigLoader`
  - liest `FileConfiguration`
  - erzwingt Mindestwerte und Defaults
  - parst `exclude-uuids` flexibel (mit/ohne Bindestriche)
- `PluginSettings` (Records)
  - `ImportSettings`
  - `DatabaseSettings`
  - `BootstrapSettings`

## Datenbank

- `DatabaseManager`
  - baut Hikari DataSource auf
  - setzt explizit MariaDB JDBC Driver (wichtig in Bukkit Classloadern)
- `SchemaBootstrapper`
  - validiert Pflichttabellen und Pflichtspalten
  - wendet bei Bedarf `db/schema.sql` an
  - importiert Seeds aus YAML per Upsert

## Import Engine

- `ImportScheduler`
  - startet periodischen Async-Task via Folia Scheduler
- `ImportCoordinator`
  - orchestriert den kompletten Importlauf
  - führt DB-Locking, File-Scan, Batch-Writes und Cleanup aus
- `StatsCalculator`
  - berechnet Metrikwerte pro Spieler
  - komprimiert canonical Stats JSON nach GZIP

## Modelle/Helfer

- `ImportSummary`: Status letzter Lauf
- `MetricSource`, `MetricValueRow`, `ComputationResult`, `PlayerProfileMeta`
- `UuidCodec`: Konvertierung UUID <-> BINARY(16), flexibles Parsing

## Import-Datenfluss (Ablauf im Lauf)

1. Import wird getriggert (`manual:<sender>` oder `timer`)
2. `running`-Flag verhindert parallele lokale läufe
3. `stats-dir` und `usercache-path` werden aufgelöst (`auto` möglich)
4. usercache-Namen werden geladen
5. DB Advisory Lock via `GET_LOCK` wird angefordert
6. Aktive `run_id` wird ermittelt oder neu erstellt (`site_state`)
7. Aktive Metrikquellen werden aus DB geladen (`metric_def.enabled=1`)
8. Vorhandene SHA1-Hashes und Profile werden in Maps geladen
9. Temporäre Tabelle `tmp_seen` wird neu erstellt
10. Alle `*.json`-Dateien werden gescannt:
11. UUID aus Dateiname lesen
12. Optional über `exclude-uuids` verwerfen
13. JSON lesen, Stats extrahieren, `_wall_banner` Keys bereinigen
14. Mindestspielzeit prüfen (`minecraft:custom.minecraft:play_time`)
15. Profilzeile erzeugen (Name aus usercache, DB oder Fallback)
16. Canonical JSON serialisieren (sortierte Keys)
17. SHA1 bilden und gegen DB vergleichen
18. Nur geänderte Spieler an Workerpool für Berechnung senden
19. Ergebnisse laufend in Batches in DB flushen
20. Nach Scan alle Restarbeiten flushen
21. Daten verwaister Spieler über `tmp_seen` bereinigen
22. Optional "king" Punkte neu berechnen
23. Optional Namen via Mojang auflösen (`import.name-resolver.enabled`)
24. Laufzeitstempel aktualisieren und Summary setzen
25. DB Lock freigeben

## Parallelisierung und Backpressure

- Main-Import läuft in einem Single-Thread Executor
- Metrikberechnungen laufen parallel im festen Workerpool (`worker-threads`)
- Ein Semaphore-Limit (`max-inflight-calculations`) begrenzt gleichzeitige Aufgaben
- Resultate werden über `ExecutorCompletionService` asynchron eingesammelt

Das Design verhindert sowohl unkontrolliertes Wachstum von Task-Queues als auch zu grosse RAM-Spitzen.

## Konsistenzstrategie

- DB `autoCommit=false`, explizite Commits nach sinnvollen Batch-Schritten
- Hash-Skip verhindert unnötige Rewrites
- `cleanupMissing` entfernt Spieler, die nicht mehr gesehen wurden
- Bei Fehlern wird `ImportSummary` trotzdem final gesetzt, damit Statusabfragen belastbar bleiben

## Namensstrategie

Priorität bei Spielernamen:

1. `usercache.json`
2. bestehender DB-Wert (`player_profile`)
3. stabiler Fallback auf UUID-Substring

Wenn Spalten vorhanden sind, werden `name_source` und `name_checked_at` gepflegt.

Optionaler Mojang-Resolver:

- Kandidaten: `fallback`/`unknown`, 12-hex Fallback-Namen, optional stale Checks via `refresh-days`
- Endpoint-Reihenfolge: Sessionserver, dann Mojang Name-History
- Bei Erfolg: `name`, `name_lc`, `name_source='mojang'`, `name_checked_at=NOW()` (falls Spalte vorhanden)
- Bei Fehlschlag: `name_checked_at=NOW()` (falls Spalte vorhanden), um sofortige Retries zu vermeiden

## "Server-König" Logik

Wenn `king-enabled=true`:

- für jede aktive Metrik werden Top-3 Spieler geholt
- Punktevergabe gemäß `king-points` (Default `5,3,1`)
- Summe pro Spieler wird als Metrik `king` in `metric_value` geschrieben
- optional werden Detailplatzierungen in `metric_award` gespeichert (wenn Tabelle existiert)


