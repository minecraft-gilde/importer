# Betrieb

## Voraussetzungen

- Java 21 (Build und Runtime)
- Paper/Folia (API `1.21`)
- MariaDB/MySQL-kompatible Datenbank (fuer Schema siehe `docs/datenbank.md`)
- Schreibrechte fuer Plugin-Datenordner
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

Empfohlen fuer neue Umgebungen:

- `bootstrap.auto-schema: true`
- `bootstrap.verify-schema: true`
- `bootstrap.seed-on-missing-schema: true`
- `bootstrap.seed-if-metric-def-empty: true`

Dann beim Start:

1. Schema pruefen
2. Bei Bedarf Schema aus Ressource anlegen
3. Bei Bedarf Seeds als Upsert importieren

## Commands

Befehl: `/statsimport run [ignorehash] [dryrun] | status | reload | resolve [max]`

- `/statsimport run`
  - startet sofortigen manuellen Import
- `/statsimport run ignorehash`
  - startet manuellen Import ohne Hash-Skip (erzwingt Recompute fuer alle behaltenen Spieler)
- `/statsimport run dryrun`
  - fuehrt einen vollstaendigen Testlauf aus, berechnet Metriken, schreibt aber nichts in die DB
- `/statsimport status`
  - zeigt Laufstatus, letzten Laufgrund, Zeitpunkte und Counter
- `/statsimport reload`
  - laedt Konfiguration/Runtime neu
  - wird verweigert, wenn ein Import oder Resolver gerade laeuft
- `/statsimport resolve`
  - startet nur die Mojang-Namensaufloesung fuer den aktiven Run
- `/statsimport resolve <max>`
  - startet Namensaufloesung mit einmaligem Limit-Override (nur fuer diesen Lauf)

Benoetigte Permission:

- `statsimporter.admin` (Default: `op`)

## Timerbetrieb

Wenn `import.enabled=true`, startet das Plugin einen periodischen Async-Import-Task:

- Initial direkt beim Pluginstart
- danach alle `import.interval-seconds`

Wenn `import.name-resolver.maintenance-enabled=true`, startet zusaetzlich ein periodischer Async-Resolver-Task:

- Initial direkt beim Pluginstart
- danach alle `import.name-resolver.maintenance-interval-seconds`
- pro Lauf mit Budget `import.name-resolver.maintenance-max-per-run`

Zusaetzlich kann direkt nach einem erfolgreichen Import ein kleiner Resolver-Lauf ausgefuehrt werden:

- `import.name-resolver.after-import-enabled`
- Budget: `import.name-resolver.after-import-max-per-run`

## Logging und Beobachtung

Relevante Logereignisse:

- aufgeloeste Inputpfade (`stats-dir`, `usercache-path`)
- Anzahl geladener Namen aus `usercache.json`
- geladene Metriken aus DB
- Resolver-Laeufe mit `candidates`, `resolved`, `failed`, `skipped`, `max`
- Abschluss mit `success`, `processed`, `kept`, `changed`, `duration`
- Fehler mit Stacktrace bei Import-, Resolver- oder Bootstrapproblemen

## Update-Prozess

1. Neue JAR deployen
2. Server starten
3. `config.yml` Diff gegen neue Defaults pruefen
4. Falls neue Metriken vorhanden, Seed-Import triggern
5. Testlauf mit `/statsimport run`
6. Status und DB-Werte stichprobenartig pruefen

## Betriebshinweise

- Starte keine zweite Importinstanz parallel gegen dieselbe DB, wenn moeglich.
- Das Plugin schuetzt sich zwar mit `GET_LOCK`, aber konsistenter Betrieb ist einfacher bei einer klaren Owner-Instanz.
- Bei grossen Datenmengen zuerst konservative Batch- und Threadwerte waehlen.
- Wenn Spielernamen veraltet wirken: Maintenance-Worker aktivieren und `maintenance-max-per-run` schrittweise erhoehen.
