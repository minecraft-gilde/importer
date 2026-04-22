﻿# Architektur

## Systemkontext

Das Plugin läuft innerhalb eines Paper/Folia Servers und übernimmt ETL-Aufgaben:

- Input: `world/stats/<uuid>.json` + `usercache.json` + `banned-players.json`
- Verarbeitung: Filter, Normierung, Metrikberechnung, Hash-Vergleich
- Output: Tabellen `player_profile`, `player_known`, `player_ban`, `player_stats`, `metric_value` (plus optional `metric_award`)

Die aktuelle Datenbasis wird über `site_state.active_run_id` verwaltet.

## Lifecycle des Plugins

Beim Start (`ImporterPlugin.onEnable`) passiert:

1. Default-Konfiguration sicherstellen (`config.yml`)
2. Seed-Datei bereitstellen (`metric-seeds.yml`)
3. Konfiguration laden und validieren (`ConfigLoader`)
4. Datenbankpool initialisieren (`DatabaseManager`, HikariCP)
5. DB-Bootstrap ausführen (`SchemaBootstrapper.ensureReady`)
6. Import-Subsystem aufbauen (`ImportCoordinator`, `ImportScheduler`, `NameMaintenanceScheduler`)
7. Import-Timer starten, falls `import.enabled=true`
8. Name-Maintenance-Timer starten, falls `import.name-resolver.maintenance-enabled=true`
9. Command `/statsimport` registrieren

Beim Stop (`onDisable`) werden beide Scheduler, Workerpools und DB-Pool sauber geschlossen.

## Komponenten und Verantwortungen

## Plugin Root

- `ImporterPlugin`
  - verdrahtet Runtime-Komponenten
  - steuert Start/Stop/Reload
  - blockiert Reload während laufendem Import/Resolver

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
- `NameMaintenanceScheduler`
  - startet periodischen Async-Task für reine Namenspflege
- `ImportCoordinator`
  - orchestriert Importläufe und Resolverläufe
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
2. `running`-Flag verhindert parallele lokale Läufe
3. `stats-dir` und `usercache-path` werden aufgelöst (`auto` möglich)
4. usercache-Namen werden geladen
5. Ban-Einträge aus `banned-players.json` werden geladen
6. DB Advisory Lock via `GET_LOCK` wird angefordert
7. Aktive `run_id` wird ermittelt oder neu erstellt (`site_state`)
8. Aktive Metrikquellen werden aus DB geladen (`metric_def.enabled=1`)
9. Vorhandene SHA1-Hashes und Profile werden in Maps geladen
10. Temp-Tabelle `tmp_seen` wird neu erstellt
11. Alle `*.json`-Dateien werden gescannt
12. UUID aus Dateiname lesen
13. Optional über `exclude-uuids` verwerfen
14. JSON lesen, Stats extrahieren, `_wall_banner` Keys bereinigen
15. Bei Parse-Fehlern wird die UUID trotzdem als `seen` markiert, damit kein Cleanup-Verlust entsteht
16. Mindestspielzeit prüfen (`minecraft:custom.minecraft:play_time`)
17. Profilzeile erzeugen (Name aus usercache, DB oder Fallback)
18. Canonical JSON serialisieren (sortierte Keys)
19. SHA1 bilden und gegen DB vergleichen
20. Nur geänderte Spieler an Workerpool für Berechnung senden
21. Ergebnisse laufend in Batches in DB flushen
22. Nach Scan Restarbeiten flushen
23. Daten verwaister Spieler über `tmp_seen` bereinigen
24. Known-Players werden per Upsert in `player_known` synchronisiert (persistent, run-unabhängig)
25. Ban-Daten werden in `player_ban` synchronisiert (pro Run kompletter Snapshot)
26. Optional "king" Punkte neu berechnen
27. Optional Name-Resolver direkt nach Import mit eigenem Budget (`after-import-max-per-run`) auf `player_profile` plus Restbudget auf `player_known`
28. Laufzeitstempel aktualisieren und Summary setzen
29. DB Lock freigeben

## Parallelisierung und Backpressure

- Main-Import läuft in einem Single-Thread Executor
- Metrikberechnungen laufen parallel im festen Workerpool (`worker-threads`)
- Ein Semaphore-Limit (`max-inflight-calculations`) begrenzt gleichzeitige Aufgaben
- Resultate werden über `ExecutorCompletionService` asynchron eingesammelt

Das Design verhindert sowohl unkontrolliertes Wachstum von Task-Queues als auch zu große RAM-Spitzen.

## Konsistenzstrategie

- DB `autoCommit=false`, explizite Commits nach sinnvollen Batch-Schritten
- Hash-Skip verhindert unnötige Rewrites
- `cleanupMissing` entfernt Spieler, die nicht mehr gesehen wurden
- Parse-Fehler löschen keine bestehenden Spielerdaten mehr implizit
- Bei Fehlern wird `ImportSummary` trotzdem final gesetzt, damit Statusabfragen belastbar bleiben

## Namensstrategie

Priorität bei Spielernamen:

1. `usercache.json`
2. bestehender DB-Wert (`player_profile`)
3. stabiler Fallback auf UUID-Substring

`name_checked_at` wird nicht mehr bei jedem usercache-Treffer auf `NOW()` gesetzt, sondern bleibt erhalten.

Optionaler Mojang-Resolver:

- Kandidaten-Priorität:
1. `fallback`/`unknown` und fehlende Namen
2. danach stale Einträge via `refresh-days`
- Endpoint-Reihenfolge: Sessionserver, dann Mojang Name-History
- Bei Erfolg: `name`, `name_lc`, `name_source='mojang'`, `name_checked_at=NOW()`
- Bei Fehlschlag: `name_checked_at=NOW()` (falls Spalte vorhanden), um sofortige Retries zu vermeiden

## "Server-König" Logik

Wenn `king-enabled=true`:

- für jede aktive Metrik werden Top-3 Spieler geholt
- Punktevergabe gemäß `king-points` (Default `5,3,1`)
- Summe pro Spieler wird als Metrik `king` in `metric_value` geschrieben
- optional werden Detailplatzierungen in `metric_award` gespeichert (wenn Tabelle existiert)

## Laufarten und Trigger

Der Importer kennt drei zentrale Laufarten:

- Timer-Import:
  - Trigger durch `ImportScheduler` mit Grund `timer`
  - optionaler globaler Hash-Bypass über `import.ignore-hash-on-timer`
- Manueller Import:
  - Trigger über `/statsimport run`
  - optionale Flags `ignorehash` und `dryrun`
- Resolver-Lauf:
  - Trigger über `/statsimport resolve [max]` oder Maintenance-Scheduler

## Dry-Run Semantik

`dryrun` führt den Importpfad inklusive Parsing und Metrikberechnung aus, schreibt aber keine Nutzdaten in die DB.

Wichtige Konsequenzen:

- kein persistentes Update an `player_profile`, `player_stats`, `metric_value`
- kein Cleanup fehlender Spieler
- keine King-Neuberechnung
- kein nachgelagerter Resolver-Lauf

## Commit- und Fehlergrenzen

Die Importlogik commitet in sinnvollen Batch-Schritten.

Architekturziel:

- bei partiellen Fehlern keine stillen Inkonsistenzen produzieren
- bereits persistierte Teilschritte nachvollziehbar halten
- Laufstatus immer im `ImportSummary` abbilden

Bei Resolver-Fehlern nach erfolgreichem Import bleiben die Importdaten committed; der Resolver-Fehler wird separat protokolliert.

## Reload- und Shutdown-Verhalten

- Reload (`/statsimport reload`) wird blockiert, solange ein Lauf aktiv ist
- Beim Plugin-Stop werden Scheduler, Worker und DB-Ressourcen geordnet geschlossen
- Trigger während Shutdown werden verworfen

Das verhindert konkurrierende Zustandswechsel beim Runtime-Rebuild.

## Invarianten für die Website-Kompatibilität

Die folgenden Architekturannahmen sind für API und Frontend kritisch:

- `site_state.active_run_id` zeigt konsistent auf den aktiven Snapshot
- Views `v_player_profile`, `v_player_stats`, `v_metric_value`, `v_player_ban` referenzieren den aktiven Snapshot
- `v_player_known` bleibt run-unabhängig
- `metric_def.enabled` steuert die sichtbaren Metriken

## Bekannte Grenzen und bewusste Trade-offs

- Snapshot-Modell:
  - optimiert für aktuelle Daten, nicht für historische Zeitreihen pro Lauf
- Resolver-Abhängigkeit:
  - Namensqualität hängt von `usercache.json` und Mojang-Upstream-Verfügbarkeit ab
- API-Grenze:
  - dieses Projekt garantiert die DB-Basis, nicht das HTTP-Verhalten der Website-API
- Lastverteilung:
  - hohe Parallelität verbessert Durchsatz, erhöht aber Spitzenlast auf DB/CPU

Diese Grenzen sind bewusst gewählt und sollten bei größeren Architekturänderungen explizit neu bewertet werden.
