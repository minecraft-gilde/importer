# Schnittstellen

Dieses Kapitel beschreibt die fachlichen und technischen Schnittstellen des Importers zu anderen Systemteilen.

## Externe Eingaben

Der Importer verarbeitet diese Dateien aus der Minecraft-Serverumgebung:

- `world/stats/<uuid>.json`
- `usercache.json`
- `banned-players.json`

### Erwartete Eigenschaften

- `stats/*.json` enthält pro Spieler UUID-basierte Rohstatistiken
- `usercache.json` dient als primäre Namensquelle
- `banned-players.json` liefert Ban-Snapshotdaten für die Anzeige

### Fehlerverhalten

- Fehlende oder unlesbare Einzeldateien führen nicht zu einem kompletten Laufabbruch
- Parse-Fehler einzelner Stats-Dateien werden protokolliert
- Bei Parse-Fehlern bleibt die UUID als `seen` markiert, damit kein versehentliches Cleanup erfolgt

## Datenbankschnittstelle

Der Importer schreibt in folgende Kernobjekte:

- `import_run`
- `site_state`
- `player_profile`
- `player_known`
- `player_ban`
- `player_stats`
- `metric_value`
- optional `metric_award`

Die Website liest primär über Views:

- `v_player_profile`
- `v_player_stats`
- `v_metric_value`
- `v_player_ban`
- `v_player_known`

## API-bezogene Vertragspunkte

Die API-Implementierung liegt im `website`-Repository, basiert jedoch auf stabilen DB-Verträgen dieses Projekts.

Wichtige Vertragsregeln:

- `site_state.active_run_id` zeigt immer auf den aktiven Snapshot
- `v_*` Views müssen konsistent auf den aktiven Snapshot zeigen
- `metric_def.enabled=1` steuert, welche Metriken in Ranglisten erscheinen
- `metric_value` muss pro `(run_id, metric_id, uuid)` eindeutig sein
- `player_stats.stats_gzip` enthält das kanonische, komprimierte Stats-JSON

## Namensauflösung (Mojang)

Der Resolver nutzt zwei Upstreams in Reihenfolge:

1. `sessionserver.mojang.com/session/minecraft/profile/<uuid>`
2. `api.mojang.com/user/profiles/<uuid>/names`

Vertragsregeln:

- Erfolgreiche Auflösung setzt `name`, `name_lc`, `name_source='mojang'`, `name_checked_at`
- Fehlgeschlagene Auflösung aktualisiert `name_checked_at`, um Hot-Loop-Retries zu vermeiden
- Resolver-Budgets (`max-per-run`, Maintenance-Budget) begrenzen Last und Laufzeit

## Kompatibilität bei Änderungen

Änderungen mit API-Auswirkung:

- Spalten-/View-Änderungen in `v_player_profile`, `v_player_stats`, `v_metric_value`, `v_player_ban`, `v_player_known`
- Semantische Änderungen an `metric_def`, `metric_source`, `metric_value`
- Änderungen an `site_state.active_run_id`-Logik

Vorgehen:

1. Änderung im Importer implementieren und dokumentieren
2. `website`-Repository auf Kompatibilität prüfen und bei Bedarf anpassen
3. Deploy erst nach erfolgreichem End-to-End-Test über Import + API + Frontend
