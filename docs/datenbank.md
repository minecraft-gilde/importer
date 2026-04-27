﻿# Datenbank

## Quelle für Schema und Seeds

- Schema: `src/main/resources/db/schema.sql`
- Seeds: `src/main/resources/metric-seeds.yml`

Beim Bootstrap validiert das Plugin Pflichtobjekte und kann Schema/Seeds automatisch anwenden.

## Datenmodell auf hoher Ebene

## Laufverwaltung

- `import_run`
  - verwaltet Importläufe (`id`, `generated_at`, `status`)
- `site_state`
  - Singleton (`id=1`) mit `active_run_id`
  - bestimmt den aktiven Daten-Snapshot

## Spieler- und Statsdaten

- `player_profile`
  - Name, Suchfeld `name_lc`, Quelleninfos, `last_seen`
- `player_known`
  - persistente Known-Players-Quelle (run-unabhängig)
  - wird aus `stats/*.json`, `usercache.json`, `banned-players.json` per Upsert gepflegt
  - Name ist immer belegt (bei fehlender Quelle: deterministischer Fallback aus UUID)
  - Resolver-Metadatum `name_checked_at` steuert Refresh für Mojang-Rechecks
  - Felder für Statuslogik: `seen_in_stats`, `seen_in_usercache`, `seen_in_bans`, `first_seen`, `last_seen`
- `player_ban`
  - aktive Bans aus `banned-players.json` pro `run_id`
  - Name ist immer belegt (bei fehlender Quelle: deterministischer Fallback aus UUID)
  - Felder für Suche/Anzeige: `name`, `reason`, `banned_by`, `banned_at`, `expires_at`, `is_permanent`
- `player_stats`
  - gzip-komprimiertes canonical Stats-JSON
  - SHA1 zur Change-Detection

## Metriken

- `metric_def`
  - Metadaten (Label, Kategorie, Unit, Sortierung, Divisor, Decimals, Enabled)
- `metric_source`
  - Mapping von Metrik-ID auf Minecraft-Statkeys (`section`, `mc_key`, `weight`)
- `metric_value`
  - materialisierte Werte pro `run_id`, `metric_id`, `uuid`
- `metric_award` (optional)
  - Top-3 Platzierungen je Metrik für Transparenz bei King-Berechnung

## Views (aktiver Snapshot)

- `v_player_profile`
- `v_player_known`
- `v_player_ban`
- `v_player_stats`
- `v_metric_value`

`v_player_profile`, `v_player_ban`, `v_player_stats` und `v_metric_value` joinen mit `site_state.active_run_id` und zeigen damit immer die aktive Datenbasis.

`v_player_known` ist eine 1:1 Sicht auf `player_known` (kein Run-Snapshot), damit die "war noch nie auf dem Server"-Prüfung nicht von `min-play-ticks` abhängt.

## Import-Schreibstrategie

Das Plugin baut jeden Import als neuen Snapshot auf:

1. bisher aktive `run_id` aus `site_state` lesen
2. neuen `import_run` mit Status `loading` anlegen
3. geänderte Spieler neu berechnen und unveränderte Spieler aus dem bisherigen Snapshot kopieren
4. Sicherheitsgrenzen für Inputgröße und übernommene Spieler prüfen
5. neuen Lauf auf `active` setzen und `site_state.active_run_id` atomar umschalten

Dadurch sehen Abfragen auf den Views während eines Imports weiterhin den letzten erfolgreichen Snapshot. Ein fehlgeschlagener Lauf bleibt unveröffentlicht und wird als `failed` markiert.

Nach erfolgreicher Veröffentlichung räumt `import.retention.keep-runs` ältere Runs auf. Die abhängigen Snapshot-Tabellen werden über Foreign Keys mit `ON DELETE CASCADE` bereinigt.

## Konsistenz und Sperren

- Advisory Lock: `SELECT GET_LOCK(lock_name, timeout)`
- Lock-Freigabe: `DO RELEASE_LOCK(lock_name)`
- Foreign Keys sichern Referenzen gegen inkonsistente Löschungen
- Batch-Commits reduzieren Lockdauer und verbessern Durchsatz

## Seeds

Aktueller Umfang (aus `metric-seeds.yml`):

- `metric_def`: 89 Einträge
- `metric_source`: 91 Einträge

Seed-Import ist idempotent:

- `metric_def`: Upsert auf ID
- `metric_source`: Upsert auf `(metric_id, section, mc_key)` mit Gewichtsupdate

Bei `bootstrap.sync-seeds: true` wird die Seed-Datei zusätzlich als Sollzustand behandelt:

- entfernte `metric_source`-Einträge werden gelöscht
- Metriken, die nicht mehr in `metric-seeds.yml` enthalten sind, werden deaktiviert
- geschützte Laufzeitmetriken wie `king` bleiben erhalten

## Nützliche SQL-Checks

## Aktive Run-ID

```sql
SELECT active_run_id FROM site_state WHERE id = 1;
```

## Anzahl aktiver Metriken

```sql
SELECT COUNT(*) AS enabled_metrics
FROM metric_def
WHERE enabled = 1;
```

## Hash-Skip Indikator (wie viele Spieler bereits bekannt)

```sql
SELECT COUNT(*) AS known_stats_rows
FROM player_stats
WHERE run_id = (SELECT active_run_id FROM site_state WHERE id = 1);
```

## Top 10 einer Metrik

```sql
SELECT uuid, value
FROM v_metric_value
WHERE metric_id = 'hours'
ORDER BY value DESC
LIMIT 10;
```

## Zusätzliche Integritätsprüfungen

## Konsistenz von `site_state`

```sql
SELECT s.id, s.active_run_id, r.id AS run_exists
FROM site_state s
LEFT JOIN import_run r ON r.id = s.active_run_id
WHERE s.id = 1;
```

Erwartung: `run_exists` ist nicht `NULL`.

## Waisenprüfung in `metric_value`

```sql
SELECT COUNT(*) AS orphan_metric_rows
FROM metric_value mv
LEFT JOIN import_run ir ON ir.id = mv.run_id
WHERE ir.id IS NULL;
```

Erwartung: `0`.

## Vergleich View vs. Basistabelle (aktive Run-ID)

```sql
SELECT
  (SELECT COUNT(*) FROM v_player_profile) AS view_count,
  (SELECT COUNT(*)
   FROM player_profile
   WHERE run_id = (SELECT active_run_id FROM site_state WHERE id = 1)) AS base_count;
```

Erwartung: `view_count == base_count`.

## Index-Strategie (kurz)

Die wichtigsten Performance-Indizes:

- `metric_value`: `(run_id, metric_id, value DESC, uuid)` für Ranglisten
- `player_profile`: `(run_id, name_lc, uuid)` für Suche und Join-Pfade
- `player_stats`: `(run_id, stats_sha1)` für Hash-Skip
- `player_known`: `(name_lc, uuid)` für run-unabhängige Namensuche

Bei Schemaänderungen sollten diese Zugriffspfade erhalten bleiben.

## Änderungsrisiken im Datenvertrag

Besonders kritisch für die Website:

- Änderungen an `v_player_profile`, `v_player_stats`, `v_metric_value`, `v_player_ban`, `v_player_known`
- Semantikänderungen von `site_state.active_run_id`
- Umbauten an `metric_def`/`metric_value`, die Sortierung oder Sichtbarkeit beeinflussen

Solche Änderungen sollten immer gemeinsam mit API/Frontend geprüft und ausgerollt werden.

Siehe auch: [schnittstellen.md](./schnittstellen.md)

## Lösch- und Migrationshinweis

`src/main/resources/db/schema.sql` im Java-Plugin ist defensiv (`CREATE TABLE IF NOT EXISTS`) und für Bootstrap in bestehenden Umgebungen geeignet.


