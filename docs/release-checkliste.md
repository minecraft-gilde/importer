# Release-Checkliste

Diese Checkliste dient als verbindlicher Ablauf für sichere Änderungen am Importer.

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

## 5. Nach dem Deploy

- Logs auf Importstart/-ende und Fehler prüfen
- `/statsimport status` auf `success=true` prüfen
- Website-Funktion stichprobenartig prüfen:
  - Ranglisten laden
  - Spielersuche funktioniert
  - Spielerprofil lädt
- Resolver-Verhalten beobachten (bei aktivierter Namenspflege)

## 6. Abschluss

- Release-Notiz mit:
  - Änderungen
  - ggf. Migrations-/Konfigurationshinweisen
  - bekannten Risiken/Nacharbeiten
- Offene Punkte als Issue erfassen
