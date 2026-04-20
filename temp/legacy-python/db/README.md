# Datenbank

Dieser Ordner enthält alles, was direkt zum Datenbankschema des Projekts gehört.

Aktuell liegen hier das Schema und ein synthetisches Beispieldataset:

```text
db/
├── README.md
├── sample-data.sql
└── schema.sql
```

## Zweck von `schema.sql`

`schema.sql` richtet die komplette Datenbankbasis für den Importer ein. Das Skript ist als All-in-one-Setup gedacht und erledigt in einem Durchlauf:

- optional das Anlegen der Datenbank `mg-stats`
- das Anlegen aller benötigten Tabellen
- das Anlegen der Indizes und Foreign Keys
- das Anlegen der Views für den aktiven Snapshot
- das Initial-Seeding von `site_state`
- das Initial-Seeding von `metric_def` und `metric_source`

## Wichtiger Hinweis

Das Schema ist für eine frische oder bewusst neu aufzubauende Datenbank gedacht. Das Skript löscht bestehende Views und Tabellen in definierter Reihenfolge und baut sie anschließend neu auf.

Wenn bereits produktive Daten vorhanden sind, sollte `schema.sql` nicht unüberlegt ausgeführt werden.

## Enthaltene Objekte

### Lauf- und Zustandsverwaltung

- `import_run`
  Speichert einzelne Importläufe mit Zeitstempel und Status.
- `site_state`
  Hält den globalen Zustand der Anwendung, insbesondere die aktuell aktive `run_id`.

### Spielerbezogene Daten

- `player_profile`
  Enthält pro Lauf und Spieler die sichtbaren Profildaten wie Name, Suchname und Zeitstempel.
- `player_stats`
  Speichert das originale Stats-JSON komprimiert als GZIP plus SHA1-Hash zur Change-Detection.

### Metriken und Leaderboards

- `metric_def`
  Definiert, welche Kennzahlen es gibt, wie sie heißen, sortiert werden und ob sie aktiv sind.
- `metric_source`
  Ordnet einer Metrik die zugrunde liegenden Minecraft-Stat-Keys zu.
- `metric_value`
  Enthält die materialisierten Werte pro Spieler, Metrik und Importlauf.
- `metric_award`
  Optionale Transparenz-Tabelle für Platzierungen und Punkte, zum Beispiel für den "Server-König".

### Views

- `v_player_profile`
- `v_player_stats`
- `v_metric_value`

Diese Views zeigen jeweils nur den aktuell aktiven Snapshot anhand von `site_state.active_run_id`.

## Seed-Daten im Schema

Das Schema bringt bereits eine initiale Konfiguration für Leaderboard-Metriken mit. Dazu gehören:

- Labels und Kategorien für die Anzeige
- Sortierreihenfolgen
- Einheiten und Divisoren für formatierte Werte
- die Minecraft-Quell-Keys je Metrik

Dadurch ist die Datenbank nach dem Einspielen nicht nur strukturell vorhanden, sondern direkt für den Importer vorkonfiguriert.

## Verwendung

Ein typischer Import des Schemas sieht zum Beispiel so aus:

```bash
mysql -u <user> -p < db/schema.sql
```

Wenn die Datenbank auf dem Zielsystem bereits existiert und nicht vom Skript angelegt werden soll, kann der `CREATE DATABASE`-Teil am Anfang von `schema.sql` auskommentiert werden.

## Zweck von `sample-data.sql`

`sample-data.sql` legt einen vollständigen synthetischen Beispieldatensatz für lokale Entwicklung an. Die Datei ergänzt das Schema um:

- genau 20 Beispielspieler
- einen aktiven Beispiel-Importlauf
- vollständige `player_profile`-Einträge
- vollständige `player_stats`-Einträge mit gzip-komprimiertem JSON
- `metric_value` für alle aktivierten Metriken
- `metric_award` für die Top-3-Platzierungen
- einen berechneten `king`-Wert auf Basis der Awards

Der Datensatz ist so aufgebaut, dass alle in `metric_source` referenzierten Minecraft-Stat-Keys im JSON vorkommen. Damit eignet er sich gut für Entwicklung, UI-Tests, Leaderboards, Suche und Demo-Umgebungen.

## Empfohlene Import-Reihenfolge

```bash
mysql -u <user> -p < db/schema.sql
mysql -u <user> -p < db/sample-data.sql
```

Nach dem Einspielen von `sample-data.sql` zeigt `site_state.active_run_id` auf den Beispiel-Datensatz.

## Zusammenspiel mit dem Importer

Die Python-Skripte im Projekt erwarten, dass dieses Schema bereits vorhanden ist. Besonders wichtig sind:

- `site_state`, damit der aktuelle aktive Lauf auflösbar ist
- `metric_def` und `metric_source`, damit überhaupt Metriken berechnet werden können
- `player_profile`, `player_stats` und `metric_value` als Zieltables für den Import

Fehlen die Metrik-Definitionen, bricht der Importer erwartungsgemäß ab.

## Hinweis zur Wiederverwendung

`sample-data.sql` verwendet eine feste `run_id` für den Beispieldatensatz und kann deshalb mehrfach ausgeführt werden, ohne jedes Mal neue Sample-Runs zu stapeln. Vorhandene echte Daten in anderen `run_id`s werden dabei nicht gelöscht.
