﻿# Gesamtstruktur

Dieses Projekt ist der technische Kern für den Import und die Aufbereitung der Minecraft-Statistiken.

## Systemüberblick

1. Der Minecraft-Server liefert Rohdaten aus `world/stats/*.json`, `usercache.json` und `banned-players.json`.
2. Das Plugin `StatsImporter` verarbeitet diese Daten und schreibt sie nach MariaDB.
3. Die Website nutzt die aufbereiteten Daten über die Statistik-API.
4. Das Frontend zeigt darauf basierend Ranglisten, Spielersuche und Profile an.

## Komponentenübersicht

- Minecraft-Server:
  - produziert Rohstatistiken und Metadateien
- StatsImporter (dieses Projekt):
  - validiert Inputs
  - berechnet Metriken
  - aktualisiert den aktiven DB-Snapshot
- Website-API (`website`-Repository):
  - stellt die Statistiken als HTTP-Endpunkte bereit
- Frontend (`website`-Repository):
  - rendert Leaderboards, Suche und Profile

## Rolle des `importer`-Repositorys

- Import-Lifecycle und Scheduler
- Konfiguration und Betrieb des Plugins
- Datenmodell, Views und Seeds
- Datenqualität, Konsistenz und Performance des Imports

Die zugehörige technische Dokumentation liegt in diesem `docs/`-Ordner.

## Betriebsgrenzen des Importers

Der Importer ist bewusst auf Snapshot-Betrieb ausgelegt:

- Fokus auf aktuelle Datenbasis statt Historie pro Lauf
- materialisierte Werte in `metric_value` für schnelle Auslieferung
- Hash-Skip für effiziente Rechen- und Schreiblast bei unveränderten Spielern

Nicht Bestandteil dieses Projekts:

- HTTP-API-Implementierung
- Frontend-Darstellung und UX-Logik
- CDN/Edge-Caching der API

## Rolle des `website`-Repositorys

- API-Endpunkte und Response-Formate
- API-Caching und Worker-Runtime
- Frontend-Integration der Statistikdaten

API-spezifische Details stehen im `website`-Repository:

- https://github.com/minecraft-gilde/website/blob/main/docs/stats-api.md

## Gemeinsamer Datenvertrag

Die Website-API arbeitet auf den vom Importer gepflegten Datenobjekten, insbesondere:

- `site_state` (aktive `run_id`)
- `v_player_profile`
- `v_player_stats`
- `v_metric_value`
- `v_player_ban`
- `v_player_known`
- `metric_def`

Details dazu stehen in [datenbank.md](./datenbank.md).

## Änderungsarten und Verantwortungsbereich

- Importer-Änderung:
  - neue Metrikquelle, Importalgorithmus, Konfigurationsparameter, Schema-/View-Änderung
  - Dokumentation in diesem Repository
- API-/Frontend-Änderung:
  - Endpunkte, Response-Felder, Caching oder UI-Verhalten
  - Dokumentation im `website`-Repository
- Vertragliche Änderung:
  - betrifft beide Repositories und wird gemeinsam ausgerollt

## Änderungen im Projekt

- Änderungen an Importlogik, Schema, Seeds oder Plugin-Konfiguration werden hier dokumentiert.
- Änderungen an API-Verhalten oder Response-Struktur werden im `website`-Repository dokumentiert.
- Wenn sich der Datenvertrag ändert, werden beide Repositories gemeinsam angepasst.

## Weiterführende Kapitel

- Architekturdetails: [architektur.md](./architektur.md)
- Betriebsrunbooks: [betrieb.md](./betrieb.md)
- Datenmodell und SQL-Checks: [datenbank.md](./datenbank.md)
- Schnittstellenvertrag: [schnittstellen.md](./schnittstellen.md)
- Ausrollprozess: [release-checkliste.md](./release-checkliste.md)
