# Betrieb

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

Befehl: `/statsimport <run|status|reload>`

- `/statsimport run`
  - startet sofortigen manuellen Import
- `/statsimport run ignorehash`
  - startet manuellen Import ohne Hash-Skip (erzwingt Recompute für alle behaltenen Spieler)
- `/statsimport status`
  - zeigt Laufstatus, letzten Laufgrund, Zeitpunkte und Counters
- `/statsimport reload`
  - lädt Konfiguration/Runtime neu
  - wird verweigert, wenn ein Import gerade läuft

Benötigte Permission:

- `statsimporter.admin` (Default: `op`)

## Timerbetrieb

Wenn `import.enabled=true`, startet das Plugin einen periodischen Async-Task:

- Initial direkt beim Pluginstart
- danach alle `import.interval-seconds`

Der Timer triggert denselben Importpfad wie manuelle Runs, inklusive DB-Lock und Summary.

## Logging und Beobachtung

Relevante Logereignisse:

- aufgelöste Inputpfade (`stats-dir`, `usercache-path`)
- Anzahl geladener Namen aus `usercache.json`
- geladene Metriken aus DB
- Abschluss mit `success`, `processed`, `kept`, `changed`, `duration`
- Fehler mit Stacktrace bei Import- oder Bootstrapproblemen

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
- Bei grossen Datenmengen zuerst konservative Batch- und Threadwerte wählen.


