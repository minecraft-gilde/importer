# Datenbank

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
  - persistente Known-Players-Quelle (run-unabhaengig)
  - wird aus `stats/*.json`, `usercache.json`, `banned-players.json` per Upsert gepflegt
  - Name ist immer belegt (bei fehlender Quelle: deterministischer Fallback aus UUID)
  - Resolver-Metadatum `name_checked_at` steuert Refresh fuer Mojang-Rechecks
  - Felder fuer Statuslogik: `seen_in_stats`, `seen_in_usercache`, `seen_in_bans`, `first_seen`, `last_seen`
- `player_ban`
  - aktive Bans aus `banned-players.json` pro `run_id`
  - Name ist immer belegt (bei fehlender Quelle: deterministischer Fallback aus UUID)
  - Felder fuer Suche/Anzeige: `name`, `reason`, `banned_by`, `banned_at`, `expires_at`, `is_permanent`
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

`v_player_known` ist eine 1:1 Sicht auf `player_known` (kein Run-Snapshot), damit die "war noch nie auf dem Server"-Pruefung nicht von `min-play-ticks` abhaengt.

## Import-Schreibstrategie

Das Plugin arbeitet in-place auf einer aktiven `run_id`:

1. aktive `run_id` lesen oder neu anlegen
2. Daten in derselben `run_id` aktualisieren
3. verwaiste Spieler über `tmp_seen` bereinigen
4. `generated_at` des Laufs aktualisieren

Dadurch können Abfragen auf den Views stabil bleiben, ohne zwischen Run-IDs umzuschalten.

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

## Lösch- und Migrationshinweis

`src/main/resources/db/schema.sql` im Java-Plugin ist defensiv (`CREATE TABLE IF NOT EXISTS`) und für Bootstrap in bestehenden Umgebungen geeignet.


