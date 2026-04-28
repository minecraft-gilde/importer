# Release-Checkliste

Diese Checkliste dient als verbindlicher Ablauf für sichere Änderungen und produktive Releases des Importers.

## 1. Vor dem Merge

- Scope der Änderung klar: Konfiguration, Importlogik, Schema, Seeds, Resolver oder Betrieb
- Betroffene Doku aktualisiert:
  - `docs/architektur.md`
  - `docs/konfiguration.md`
  - `docs/betrieb.md`
  - `docs/datenbank.md`
  - `docs/schnittstellen.md`
- Lokaler Build erfolgreich:
  - `.\gradlew.bat build`
- CI (`.github/workflows/ci.yml`) grün

## 2. DB- und Vertragsprüfung

- `schema.sql` bleibt konsistent mit Laufzeitannahmen
- Seed-Änderungen geprüft:
  - `metric_def` IDs stabil und eindeutig
  - `metric_source` Mappings korrekt
- API-relevante DB-Objekte unverändert oder bewusst angepasst:
  - `site_state`
  - `v_player_profile`
  - `v_player_stats`
  - `v_metric_value`
  - `v_player_ban`
  - `v_player_known`

## 3. Staging-/Testserverlauf

1. Neue JAR deployen
2. Server starten
3. `/statsimport run` ausführen
4. `/statsimport status` prüfen
5. Optional `/statsimport resolve <max>` ausführen
6. SQL-Stichproben prüfen:
   - aktive Run-ID
   - Datensätze in `v_player_profile`
   - Datensätze in `v_metric_value`
   - Plausible Top-Werte

## 4. Produktionsfreigabe

- Wartungsfenster kommuniziert (falls nötig)
- DB-Backup vorhanden
- Konfigurationsdiff zur Vorversion geprüft
- Rollback-Pfad vorbereitet:
  - letzte funktionierende JAR verfügbar
  - bekannte Konfigurationsversion verfügbar

## 5. GitHub Release erstellen

Releases werden automatisch durch `.github/workflows/release.yml` gebaut und veröffentlicht.

Voraussetzungen:

- alle Änderungen sind auf `main` gemerged
- CI auf `main` ist grün
- Versionsnummer steht fest, zum Beispiel `1.0.0`
- Release-Notizen oder PR-Liste sind geprüft
- Repository-Setting `Actions > General > Workflow permissions` erlaubt Schreibzugriff, damit `GITHUB_TOKEN` Releases anlegen kann

Ablauf:

```powershell
git checkout main
git pull --ff-only
git tag v1.0.0
git push origin v1.0.0
```

Danach startet GitHub Actions automatisch den Workflow `Release`.
Der Workflow:

- validiert den Tag (`v1.0.0` oder z. B. `v1.0.0-rc.1`)
- baut mit `./gradlew clean build -PreleaseVersion=1.0.0 --no-daemon`
- setzt die Version in `plugin.yml` und `paper-plugin.yml`
- veröffentlicht `StatsImporter-1.0.0.jar` unter GitHub Releases
- veröffentlicht zusätzlich `StatsImporter-1.0.0.jar.sha256`

Prüfung nach dem Workflow:

- GitHub Action `Release` ist grün
- unter `Releases` existiert ein Release für `v1.0.0`
- Asset `StatsImporter-1.0.0.jar` ist vorhanden
- optional Checksumme lokal prüfen:

```powershell
Get-FileHash .\StatsImporter-1.0.0.jar -Algorithm SHA256
```

Wenn ein Workflow erneut laufen muss, darf derselbe Tag erneut verwendet werden. Der Workflow überschreibt vorhandene Release-Assets mit gleichem Namen.

## 6. Nach dem Deploy

- Logs auf Importstart/-ende und Fehler prüfen
- `/statsimport status` auf `success=true` prüfen
- Website-Funktion stichprobenartig prüfen:
  - Ranglisten laden
  - Spielersuche funktioniert
  - Spielerprofil lädt
- Resolver-Verhalten beobachten (bei aktivierter Namenspflege)

## 7. Abschluss

- Release-Notiz mit:
  - Änderungen
  - ggf. Migrations-/Konfigurationshinweisen
  - bekannten Risiken/Nacharbeiten
- Offene Punkte als Issue erfassen
