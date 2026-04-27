﻿# Betrieb

## Voraussetzungen

- Java 21 (Build und Runtime)
- Paper/Folia (API `1.21`)
- MariaDB/MySQL-kompatible Datenbank (für Schema siehe `docs/datenbank.md`)
- Schreibrechte für Plugin-Datenordner
- Leserechte auf `world/stats` und `usercache.json`

## Build

Windows PowerShell:

```powershell
.\gradlew.bat build
```

Linux/macOS:

```bash
./gradlew build
```

Ergebnis:

- `build/libs/stats-importer-plugin-1.0.0.jar`

## Deployment

1. JAR nach `plugins/` kopieren
2. Server starten
3. Plugin erzeugt initial:
4. `plugins/StatsImporter/config.yml`
5. `plugins/StatsImporter/metric-seeds.yml`
6. `config.yml` auf Zielumgebung anpassen
7. Server oder Plugin neu laden

## Erster Start mit DB-Bootstrap

Empfohlen für neue Umgebungen:

- `bootstrap.auto-schema: true`
- `bootstrap.verify-schema: true`
- `bootstrap.seed-on-missing-schema: true`
- `bootstrap.seed-if-metric-def-empty: true`

Dann beim Start:

1. Schema prüfen
2. Bei Bedarf Schema aus Ressource anlegen
3. Bei Bedarf Seeds als Upsert importieren

## Commands

Befehl: `/statsimport run [ignorehash] [dryrun] | status | reload | resolve [max]`

- `/statsimport run`
  - startet sofortigen manuellen Import
- `/statsimport run ignorehash`
  - startet manuellen Import ohne Hash-Skip (erzwingt Recompute für alle behaltenen Spieler)
- `/statsimport run dryrun`
  - führt einen vollständigen Testlauf aus, berechnet Metriken, schreibt aber nichts in die DB
- `/statsimport status`
  - zeigt Laufstatus, letzten Laufgrund, Zeitpunkte und Counter
- `/statsimport reload`
  - lädt Konfiguration/Runtime neu
  - wird verweigert, wenn ein Import oder Resolver gerade läuft
- `/statsimport resolve`
  - startet nur die Mojang-Namensauflösung für den aktiven Run
- `/statsimport resolve <max>`
  - startet Namensauflösung mit einmaligem Limit-Override (nur für diesen Lauf)

Benötigte Permission:

- `statsimporter.admin` (Default: `op`)

## Timerbetrieb

Wenn `import.enabled=true`, startet das Plugin einen periodischen Async-Import-Task:

- Initial direkt beim Pluginstart
- danach alle `import.interval-seconds`
- Default: `14400` Sekunden (4 Stunden)

Für Live-Betrieb ist ein Intervall von 3-4 Stunden sinnvoll: deutlich aktueller als zweimal täglich, aber ohne unnötige Dauerlast auf Server, DB und Mojang-Resolver. Falls die Website keine nahezu-live Ranglisten braucht, ist `14400` der ruhigere Startwert.

Mit dem Snapshot-Modell erzeugt jeder erfolgreiche Import einen neuen Run. `import.retention.keep-runs: 1` hält nur den zuletzt veröffentlichten Snapshot und löscht ältere Runs samt abhängigen Snapshot-Daten automatisch.

Wenn `import.name-resolver.maintenance-enabled=true`, startet zusätzlich ein periodischer Async-Resolver-Task:

- Initial direkt beim Pluginstart
- danach alle `import.name-resolver.maintenance-interval-seconds`
- pro Lauf mit Budget `import.name-resolver.maintenance-max-per-run`

Zusätzlich kann direkt nach einem erfolgreichen Import ein kleiner Resolver-Lauf ausgeführt werden:

- `import.name-resolver.after-import-enabled`
- Budget: `import.name-resolver.after-import-max-per-run`

Dieser Resolver-Lauf wird erst nach der Snapshot-Veröffentlichung in die gemeinsame Resolver-Queue gelegt. Maintenance- und manuelle Resolver-Läufe nutzen dieselbe Queue, werden dedupliziert und laufen nie parallel zum Import oder zueinander.

## Logging und Beobachtung

Relevante Logereignisse:

- aufgelöste Inputpfade (`stats-dir`, `usercache-path`)
- Anzahl geladener Namen aus `usercache.json`
- geladene Metriken aus DB
- Parse-Fehler bei einzelnen Stats-Dateien (`parseErrors`)
- Resolver-Läufe mit `candidates`, `resolved`, `failed`, `skipped`, `max`
- Abschluss mit `success`, `processed`, `kept`, `changed`, `duration`
- Fehler mit Stacktrace bei Import-, Resolver- oder Bootstrapproblemen

## Update-Prozess

1. Neue JAR deployen
2. Server starten
3. `config.yml` Diff gegen neue Defaults prüfen
4. Falls neue Metriken vorhanden, Seed-Import triggern
5. Testlauf mit `/statsimport run`
6. Status und DB-Werte stichprobenartig prüfen

## Betriebshinweise

- Starte keine zweite Importinstanz parallel gegen dieselbe DB, wenn möglich.
- Das Plugin schützt sich zwar mit `GET_LOCK`, aber konsistenter Betrieb ist einfacher bei einer klaren Owner-Instanz.
- Bei großen Datenmengen zuerst konservative Batch- und Threadwerte wählen.
- Wenn Spielernamen veraltet wirken: Maintenance-Worker aktivieren und `maintenance-max-per-run` schrittweise erhöhen.

## Runbook: Erstinbetriebnahme (produktionstauglich)

1. DB-Zugriff und Rechte sicherstellen (`CREATE`, `ALTER`, `VIEW`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`)
2. Plugin deployen und Start mit aktivem Bootstrap durchführen
3. Konfiguration für Zielumgebung setzen (`database.*`, `import.*`)
4. Manuellen Lauf starten: `/statsimport run`
5. Laufstatus prüfen: `/statsimport status`
6. Safety-Grenzen passend zur echten Servergröße setzen (`import.safety.min-processed-files`, `import.safety.min-kept-players`)
7. SQL-Stichprobe auf aktive Views und Top-Metriken
8. Resolver-Budgets aktivieren und beobachten (falls Namenspflege gewünscht)
9. Erst danach Timerbetrieb dauerhaft freigeben

## Runbook: Wiederanlauf nach Fehler/Abbruch

1. Ursache im Log identifizieren (Pfad, DB, Lock, Upstream)
2. Konfiguration/Pfade korrigieren
3. Optional einmalig `ignorehash` nutzen, wenn vollständiger Recompute nötig ist
4. Lauf erneut starten: `/statsimport run`
5. Ergebnis auf `success`, `processed`, `kept`, `changed` verifizieren
6. Bei Namensproblemen Resolver separat starten: `/statsimport resolve [max]`

## Backup- und Rollback-Empfehlung

Vor größeren Änderungen:

- aktuelles DB-Backup erstellen
- letzte funktionierende JAR bereithalten
- aktuelle `config.yml` sichern

Rollback im Störfall:

1. fehlerhafte JAR entfernen
2. letzte stabile JAR deployen
3. gesicherte Konfiguration einspielen
4. Testlauf ausführen und Datenkonsistenz prüfen

## Betriebskennzahlen, die regelmäßig beobachtet werden sollten

- Laufdauer (`duration`)
- verarbeitete Dateien (`processed`)
- übernommene Spieler (`kept`)
- geänderte Spieler (`changed`)
- Lock-Fehler (`Could not acquire DB lock`)
- Resolver-Outcome (`resolved`, `failed`, `skipped`)

## Sicherheitscheck für den Betrieb

- DB-Zugangsdaten nur serverseitig speichern
- Admin-Permission `statsimporter.admin` nur an vertraute Rollen vergeben
- Keine Parallelinstanzen mit derselben DB ohne abgestimmtes Locking-Konzept
- Logs regelmäßig prüfen und rotieren

## Ergänzende Kapitel

- Datenvertrag und Systemgrenzen: [gesamtstruktur.md](./gesamtstruktur.md)
- Technischer Schnittstellenvertrag: [schnittstellen.md](./schnittstellen.md)
- Verbindlicher Ausrollablauf: [release-checkliste.md](./release-checkliste.md)
