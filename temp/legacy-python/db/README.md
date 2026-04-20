# Datenbank (Java-Plugin)

Diese Dokumentation beschreibt das Datenbankschema des **Stats Importer Java-Plugins** (Paper/Folia).

## Relevante Dateien

- `src/main/resources/db/schema.sql`
- `src/main/resources/metric-seeds.yml`

`schema.sql` enthält die Tabellen und Views. `metric-seeds.yml` enthält die initialen Metrikdefinitionen und Quellenzuordnungen für `metric_def` und `metric_source`.

## Was das Java-Plugin beim Start machen kann

Das Verhalten wird über `bootstrap.*` in der `config.yml` gesteuert:

- `bootstrap.auto-schema`: Führt bei fehlendem oder unvollständigem Schema automatisch `db/schema.sql` aus.
- `bootstrap.verify-schema`: Validiert, ob alle erforderlichen Tabellen/Spalten vorhanden sind.
- `bootstrap.seed-on-missing-schema`: Importiert Seeds nach einem Schema-Bootstrap.
- `bootstrap.seed-if-metric-def-empty`: Importiert Seeds, wenn `metric_def` leer ist.
- `bootstrap.seed-file`: Pfad zur Seed-Datei (standardmäßig `metric-seeds.yml` im Plugin-Datenordner).

Der Seed-Import erfolgt als Upsert, damit bestehende Metriken aktualisiert und fehlende ergänzt werden.

## Enthaltene DB-Objekte

### Lauf- und Zustandsverwaltung

- `import_run`: Speichert Importläufe mit Zeitstempel und Status.
- `site_state`: Hält den globalen Zustand, insbesondere `active_run_id`.

### Spielerbezogene Daten

- `player_profile`: Sichtbare Profildaten pro Spieler und Lauf.
- `player_stats`: Komprimiertes Stats-JSON (`stats_gzip`) plus Hash (`stats_sha1`) zur Change-Detection.

### Metriken und Leaderboards

- `metric_def`: Metrikdefinitionen (Label, Kategorie, Sortierung, Einheiten, Aktiv-Flag).
- `metric_source`: Zuordnung von Metriken zu Minecraft-Stat-Keys.
- `metric_value`: Materialisierte Metrikwerte pro Spieler und Lauf.
- `metric_award`: Optionale Platzierungen/Punkte (z. B. für den `king`-Wert).

### Views (aktiver Snapshot)

- `v_player_profile`
- `v_player_stats`
- `v_metric_value`

Die Views zeigen den aktiven Snapshot über `site_state.active_run_id`.

## Manuelles Setup (optional)

Wenn du das Schema nicht per Plugin-Bootstrap anlegen willst, kannst du es manuell einspielen:

```bash
mysql -u <user> -p < src/main/resources/db/schema.sql
```

Die Seed-Daten können anschließend über die Plugin-Bootstrap-Einstellungen eingespielt werden.

## Zusammenspiel mit dem Java-Plugin

Das Plugin arbeitet auf Basis dieses Schemas und nutzt insbesondere:

- `site_state` zur Auflösung des aktiven Laufs
- `metric_def` und `metric_source` zur Metrikberechnung
- `player_profile`, `player_stats` und `metric_value` als zentrale Import-Zieltabellen

Fehlen diese Strukturen, schlägt die Schema-Validierung fehl und das Plugin startet den Import nicht.
