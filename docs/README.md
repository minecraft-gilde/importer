﻿# Projektdokumentation: StatsImporter

Diese Dokumentation beschreibt das Java-Plugin `StatsImporter` für Paper/Folia Server.
Sie richtet sich an zwei Zielgruppen:

- Serverbetrieb: Installation, Konfiguration, Betrieb, Fehlerbehebung
- Entwicklung: Architektur, Datenfluss, Datenbank und Erweiterungspunkte

## Inhaltsverzeichnis

- [00 Gesamtstruktur](./gesamtstruktur.md)
- [01 Architektur](./architektur.md)
- [02 Konfiguration](./konfiguration.md)
- [03 Betrieb](./betrieb.md)
- [04 Datenbank](./datenbank.md)
- [05 Entwicklung](./entwicklung.md)
- [06 Troubleshooting](./troubleshooting.md)
- [07 Schnittstellen](./schnittstellen.md)
- [08 Release-Checkliste](./release-checkliste.md)

## Kurzübersicht

`StatsImporter` importiert Minecraft-Statistiken aus `world/stats/*.json` in MariaDB und materialisiert daraus Metrikwerte für Leaderboards.

Kernmerkmale:

- Laufender Import als Folia/Paper-Async-Task
- Hash-basierte Change-Detection pro Spielerdatei
- Batch- und Streaming-Schreibvorgänge für große Datenmengen
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

## Website-API

API-Endpunkte, Response-Formate und Runtime für das Frontend liegen im `website`-Repository:

- https://github.com/minecraft-gilde/website/blob/main/docs/stats-api.md

Für den technischen Übergang zwischen Importer und Website siehe zusätzlich:

- [07 Schnittstellen](./schnittstellen.md)


