# Projektdokumentation: StatsImporter

Diese Dokumentation beschreibt das Java-Plugin `StatsImporter` für Paper/Folia Server.
Sie richtet sich an zwei Zielgruppen:

- Serverbetrieb: Installation, Konfiguration, Betrieb, Fehlerbehebung
- Entwicklung: Architektur, Datenfluss, Datenbank und Erweiterungspunkte

## Inhaltsverzeichnis

- [01 Architektur](./architektur.md)
- [02 Konfiguration](./konfiguration.md)
- [03 Betrieb](./betrieb.md)
- [04 Datenbank](./datenbank.md)
- [05 Entwicklung](./entwicklung.md)
- [06 Troubleshooting](./troubleshooting.md)

## Kurzübersicht

`StatsImporter` importiert Minecraft-Statistiken aus `world/stats/*.json` in MariaDB und materialisiert daraus Metrikwerte für Leaderboards.

Kernmerkmale:

- Laufender Import als Folia/Paper-Async-Task
- Hash-basierte Change-Detection pro Spielerdatei
- Batch- und Streaming-Schreibvorgänge für grosse Datenmengen
- DB Advisory Lock (`GET_LOCK`) gegen parallele Importe
- Automatisches DB-Bootstrap inkl. Seeds für `metric_def` und `metric_source`
- Optionale "Server-König"-Punkteberechnung

## Schnellstart

1. Build erzeugen: `.\gradlew.bat build`
2. JAR deployen: `build/libs/stats-importer-plugin-1.0.0.jar` nach `plugins/`
3. Server starten, `plugins/StatsImporter/config.yml` anpassen
4. Bei Bedarf DB-Schema/Seeds beim Start automatisch anlegen lassen (`bootstrap.*`)
5. Import testen: `/statsimport run`
6. Status prüfen: `/statsimport status`

## Wichtige Projektpfade

- `src/main/java/de/gilde/statsimporter` - Plugin-Code
- `src/main/resources/config.yml` - Default-Konfiguration
- `src/main/resources/db/schema.sql` - DB-Schema
- `src/main/resources/metric-seeds.yml` - Seed-Daten für Metriken
- `temp/legacy-python` - Altes Python-Importer-Setup (nur Referenz/Migration)


