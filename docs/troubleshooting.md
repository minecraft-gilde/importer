﻿# Troubleshooting

## Plugin startet nicht

Symptom:

- Log zeigt `Could not start plugin runtime`

prüfen:

1. `config.yml` vorhanden und valide?
2. DB erreichbar (`host`, `port`, `name`, `user`, `password`)?
3. Pflichtfelder gesetzt (`database.host`, `database.name`, `database.user`)?
4. Schema-Validierung erfolgreich?

## `Database bootstrap failed`

Mögliche Ursachen:

- DB-Benutzer hat keine Rechte für `CREATE TABLE` / `ALTER` / `VIEW`
- `seed-file` nicht auffindbar
- Schema weiterhin unvollständig trotz `auto-schema`

Lösung:

- Rechte des DB-Users prüfen
- Pfad `bootstrap.seed-file` kontrollieren
- bei Bedarf Schema manuell einspielen und `verify-schema` erneut testen

## `No enabled metrics found. Seed metric_def + metric_source first.`

Ursache:

- `metric_def`/`metric_source` leer oder alle Metriken deaktiviert

Lösung:

1. Seed-Import aktivieren (`bootstrap.seed-if-metric-def-empty: true`)
2. Plugin neu starten
3. oder Daten manuell in `metric_def`/`metric_source` einspielen

## `Could not acquire DB lock 'mc_stats_import'`

Ursache:

- Ein weiterer Import hält den Advisory Lock

Lösung:

- Parallele Importjobs vermeiden
- Intervall vergrößern
- `import.db-lock-timeout-seconds` erhöhen

## `stats-dir not found: ...`

Ursache:

- Pfad falsch oder nicht lesbar

Lösung:

1. bei `auto`: prüfen, welcher World-Ordner vom Server genutzt wird
2. alternativ festen absoluten Pfad setzen (`import.stats-dir`)
3. Dateirechte kontrollieren

## Namen sehen falsch aus oder bleiben Fallback

Hintergrund:

- Der Import selbst nutzt zuerst `usercache.json`; optionale Mojang-Auflösung läuft nur bei aktivem Resolver.

Lösung:

- `import.usercache-path` prüfen
- sicherstellen, dass `usercache.json` aktuell ist
- optional vorhandene DB-Namen als Zwischenquelle nutzen (wird automatisch gemacht)
- `import.name-resolver.enabled: true` setzen
- bei großen Servern `import.name-resolver.max-per-run` erhöhen (z. B. `5000`)
- bei Rate-Limits `import.name-resolver.sleep-ms` erhöhen (z. B. `250-500`)

## `/statsimport reload` funktioniert nicht während Import

Verhalten ist beabsichtigt:

- Reload wird geblockt, solange ein Lauf aktiv ist, um inkonsistente Runtime-Zustände zu verhindern.

Lösung:

- Importende abwarten
- danach erneut `/statsimport reload` ausführen

## Performanceprobleme

Symptome:

- sehr lange Importdauer
- hohe DB-Latenzen
- hohe CPU-Last

Maßnahmen:

1. `worker-threads` schrittweise anpassen
2. `max-inflight-calculations` reduzieren, wenn RAM/DB unter Druck geraten
3. `flush-*` Größer setzen für weniger Roundtrips (oder kleiner bei Peakdruck)
4. DB-Indizes und I/O prüfen

## Daten verschwinden nach Import

Hintergrund:

- Der aktive Snapshot wird erst nach erfolgreichem Import umgeschaltet
- Spieler, die im neuen Snapshot nicht importiert werden, erscheinen nach der Umschaltung nicht mehr in den aktiven Views

Typische Gründe:

- Spielerdatei fehlt in `stats-dir`
- Spieler fällt unter `min-play-ticks`
- UUID steht in `exclude-uuids`

prüfen:

- Inputdateien im Stats-Ordner
- Filterwerte in `config.yml`
- Ausschlussliste

## Import läuft erfolgreich, aber `changed=0`

Hintergrund:

- Hash-Skip überspringt unveränderte Spieler bewusst.

Prüfen:

1. Wurden Stats-Dateien seit letztem Lauf tatsächlich geändert?
2. Wurde versehentlich mit identischem Datenstand getestet?
3. Falls Vollrecompute gewünscht: `/statsimport run ignorehash`

## Dry-Run erzeugt keine neuen Daten

Hintergrund:

- Verhalten ist korrekt: Dry-Run schreibt keine Daten in die DB.

Prüfen:

1. Wurde `/statsimport run dryrun` statt `/statsimport run` verwendet?
2. Erwartung an den Laufmodus im Team klar dokumentieren

## Resolver startet nicht

Typische Ursachen:

- Ein Import- oder Resolverlauf ist bereits aktiv
- `import.name-resolver.enabled` ist deaktiviert
- `max`-Parameter ungültig (`< 1` oder kein Integer)

Prüfen:

1. `/statsimport status`
2. Konfiguration `import.name-resolver.*`
3. Aufrufsyntax `/statsimport resolve [max]`

## Views liefern leeres Ergebnis trotz vorhandener Basistabellen

Typische Ursachen:

- `site_state.active_run_id` ist `NULL` oder zeigt auf nicht vorhandenen Lauf
- Importlauf hat den Snapshot nicht aktivieren können

Prüfen:

```sql
SELECT active_run_id FROM site_state WHERE id = 1;
SELECT id, generated_at, status FROM import_run ORDER BY id DESC LIMIT 5;
```

## Hohe DB-Last während Import

Typische Stellhebel:

- `worker-threads` reduzieren
- `max-inflight-calculations` reduzieren
- `flush-*` anpassen (zu große Batches können Peaks erzeugen)
- Importintervall vergrößern

Vorgehen:

1. Immer nur einen Parameter gleichzeitig ändern
2. Nach jeder Änderung einen vollständigen Lauf messen

## Ergänzende Kapitel

- Konfigurationsdetails und Grenzwerte: [konfiguration.md](./konfiguration.md)
- Betriebsrunbooks: [betrieb.md](./betrieb.md)
- SQL-Integritätschecks: [datenbank.md](./datenbank.md)


