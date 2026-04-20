# Troubleshooting

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
- Intervall verGrößern
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

- Das Java-Plugin nutzt primär `usercache.json`, kein aktives Mojang-Resolver-Jobsystem

Lösung:

- `import.usercache-path` prüfen
- sicherstellen, dass `usercache.json` aktuell ist
- optional vorhandene DB-Namen als Zwischenquelle nutzen (wird automatisch gemacht)

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

Massnahmen:

1. `worker-threads` schrittweise anpassen
2. `max-inflight-calculations` reduzieren, wenn RAM/DB unter Druck geraten
3. `flush-*` Größer setzen für weniger Roundtrips (oder kleiner bei Peakdruck)
4. DB-Indizes und I/O prüfen

## Daten verschwinden nach Import

Hintergrund:

- `cleanupMissing` entfernt Spieler, die im aktuellen Lauf nicht in `tmp_seen` gelandet sind

Typische Gründe:

- Spielerdatei fehlt in `stats-dir`
- Spieler fällt unter `min-play-ticks`
- UUID steht in `exclude-uuids`

prüfen:

- Inputdateien im Stats-Ordner
- Filterwerte in `config.yml`
- Ausschlussliste


