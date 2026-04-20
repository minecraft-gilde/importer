# Entwicklung

## Projektstruktur

```text
src/main/java/de/gilde/statsimporter
  ImporterPlugin.java
  command/
  config/
  db/
  importer/
  model/

src/main/resources
  plugin.yml
  config.yml
  metric-seeds.yml
  db/schema.sql
```

## Build und lokale Checks

Build:

```powershell
.\gradlew.bat build
```

Sinnvolle Zusatzschritte:

- JAR in lokalen Testserver deployen
- manuellen Importlauf ausführen
- Importstatus und DB-Ergebnis prüfen

## Entwicklungsworkflow (empfohlen)

1. Änderung in kleinem Scope umsetzen
2. Build lokal ausführen
3. Auf Testserver testen (`/statsimport run`, `/statsimport status`)
4. DB-Stichproben gegen erwartete Metriken fahren
5. Erst dann in höhere Umgebung übernehmen

## Erweiterung: neue Metrik

1. `metric-seeds.yml` erweitern:
2. neuen `metric-definitions` Eintrag anlegen
3. passende `metric-sources` Zuordnungen ergänzen
4. Plugin mit Seed-Import starten (oder manuell einspielen)
5. Importlauf ausführen
6. Ergebnis in `metric_value` validieren

Wichtig:

- `metric-id` muss eindeutig sein
- `section`/`mc-key` müssen exakt zu Minecraft Stats JSON passen
- Aggregation über mehrere Quellen erfolgt per Summe (`value * weight`)

## Erweiterung: neues Konfigurationsfeld

1. `src/main/resources/config.yml` erweitern
2. `PluginSettings` Record anpassen
3. `ConfigLoader.load` inkl. Validierung erweitern
4. Nutzung in Runtime-Komponente implementieren
5. Dokumentation unter `docs/konfiguration.md` aktualisieren

## Erweiterung: Importlogik

Betroffene Hauptklasse: `ImportCoordinator`

Wichtige Invarianten:

- Keine parallelen Runs innerhalb einer Instanz (`AtomicBoolean running`)
- DB-Lock muss immer freigegeben werden
- Batch-Flushes dürfen keine Daten verlieren
- Cleanup darf nur auf `tmp_seen`-Basis der aktuellen Runde arbeiten
- Summary muss auch bei Fehlern final gesetzt werden

## Performance-Tuning Ansatz

Bei Durchsatzproblemen iterativ anpassen:

1. `worker-threads`
2. `max-inflight-calculations`
3. `flush-*` BatchGrößen
4. DB-Indizes/Hardware prüfen

Aendere nie alle Regler gleichzeitig, damit Ursache und Wirkung erkennbar bleiben.

## Test-Checkliste (manuell)

- Startet Plugin ohne Fehler?
- Werden Schema und Seeds wie erwartet angewendet?
- Funktioniert `/statsimport run`?
- Werden nur geänderte Spieler neu berechnet (Hash-Skip)?
- Wird Cleanup korrekt ausgeführt, wenn Spielerdateien fehlen?
- Wird "king" korrekt gebildet?
- Bleibt `/statsimport reload` bei laufendem Import geblockt?

## Legacy-Hinweis

`temp/legacy-python` enthält den früheren Python-Importer inklusive Schema/Runner.
Dieser Ordner ist kein aktiver Teil der Java-Plugin-Runtime, kann aber als Referenz für Migrationsvergleich dienen.


