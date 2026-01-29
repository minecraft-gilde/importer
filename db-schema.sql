-- ============================================================================
-- Minecraft Statistik – All-in-one Schema + Seeds (MariaDB/MySQL)
--
-- What this does:
--   * Creates database (optional), tables, indexes, foreign keys, and views
--   * Seeds metric_def + metric_source (your "configured" metrics)
--   * Seeds site_state row (id=1) so the "current snapshot" views work
--
-- Notes:
--   * This is intended for a FRESH/EMPTY database.
--   * Change the DB name below if your host uses a different database name.
-- ============================================================================

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
SET time_zone = "+00:00";
SET NAMES utf8mb4;

-- --- Database (optional; comment out if your host pre-creates the DB) ---
CREATE DATABASE IF NOT EXISTS `mg-stats`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE `mg-stats`;

START TRANSACTION;

-- --- Drop views first (they depend on tables) ---
DROP VIEW IF EXISTS `v_metric_value`;
DROP VIEW IF EXISTS `v_player_profile`;
DROP VIEW IF EXISTS `v_player_stats`;

-- --- Drop tables (reverse dependency order) ---
DROP TABLE IF EXISTS `metric_award`;
DROP TABLE IF EXISTS `metric_value`;
DROP TABLE IF EXISTS `metric_source`;
DROP TABLE IF EXISTS `metric_def`;
DROP TABLE IF EXISTS `player_stats`;
DROP TABLE IF EXISTS `player_profile`;
DROP TABLE IF EXISTS `site_state`;
DROP TABLE IF EXISTS `import_run`;

-- ============================================================================
-- Core tables
-- ============================================================================

CREATE TABLE `import_run` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `generated_at` DATETIME NOT NULL,
  `status`       ENUM('loading','active','failed') NOT NULL DEFAULT 'loading',
  `note`         VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`,`generated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `site_state` (
  `id`            TINYINT UNSIGNED NOT NULL,
  `active_run_id` BIGINT UNSIGNED DEFAULT NULL,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_active_run` (`active_run_id`),
  CONSTRAINT `fk_active_run` FOREIGN KEY (`active_run_id`) REFERENCES `import_run` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Important: seed singleton row so the "current snapshot" views work
INSERT INTO `site_state` (`id`, `active_run_id`) VALUES (1, NULL);

CREATE TABLE `player_profile` (
  `run_id`          BIGINT UNSIGNED NOT NULL,
  `uuid`            BINARY(16) NOT NULL,
  `name`            VARCHAR(16) NOT NULL,
  `name_lc`         VARCHAR(16) NOT NULL,
  `name_source`     VARCHAR(16) NOT NULL DEFAULT 'unknown',
  `name_checked_at` DATETIME DEFAULT NULL,
  `last_seen`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`run_id`,`uuid`),
  KEY `idx_name_search` (`run_id`,`name_lc`,`uuid`),
  KEY `idx_name_refresh` (`run_id`,`name_source`,`name_checked_at`),
  KEY `idx_last_seen_run` (`run_id`,`last_seen`),
  CONSTRAINT `fk_pp_run` FOREIGN KEY (`run_id`) REFERENCES `import_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `player_stats` (
  `run_id`     BIGINT UNSIGNED NOT NULL,
  `uuid`       BINARY(16) NOT NULL,
  `stats_gzip` MEDIUMBLOB NOT NULL,
  `stats_sha1` BINARY(20) NOT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`run_id`,`uuid`),
  KEY `idx_hash` (`run_id`,`stats_sha1`),
  CONSTRAINT `fk_ps_run` FOREIGN KEY (`run_id`) REFERENCES `import_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================================
-- Metric configuration + materialized values
-- ============================================================================

CREATE TABLE `metric_def` (
  `id`         VARCHAR(64)  NOT NULL,
  `label`      VARCHAR(128) NOT NULL,
  `category`   VARCHAR(64)  NOT NULL DEFAULT 'Allgemein',
  `unit`       VARCHAR(32)  DEFAULT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  `enabled`    TINYINT(1) NOT NULL DEFAULT 1,
  `divisor`    BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `decimals`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `metric_source` (
  `metric_id` VARCHAR(64)  NOT NULL,
  `section`   VARCHAR(64)  NOT NULL,
  `mc_key`    VARCHAR(128) NOT NULL,
  `weight`    INT NOT NULL DEFAULT 1,
  PRIMARY KEY (`metric_id`,`section`,`mc_key`),
  CONSTRAINT `fk_ms_metric` FOREIGN KEY (`metric_id`) REFERENCES `metric_def` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `metric_value` (
  `run_id`    BIGINT UNSIGNED NOT NULL,
  `metric_id` VARCHAR(64) NOT NULL,
  `uuid`      BINARY(16) NOT NULL,
  `value`     BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`run_id`,`metric_id`,`uuid`),
  KEY `idx_metric_sort` (`run_id`,`metric_id`,`value` DESC,`uuid`),
  KEY `idx_player_metrics` (`run_id`,`uuid`,`metric_id`),
  KEY `fk_mv_metric` (`metric_id`),
  CONSTRAINT `fk_mv_run`    FOREIGN KEY (`run_id`) REFERENCES `import_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_mv_metric` FOREIGN KEY (`metric_id`) REFERENCES `metric_def` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Optional: Top-3 award materialization (used for "Server-König" transparency)
CREATE TABLE `metric_award` (
  `run_id`    BIGINT UNSIGNED NOT NULL,
  `metric_id` VARCHAR(64) NOT NULL,
  `place`     TINYINT UNSIGNED NOT NULL,
  `uuid`      BINARY(16) NOT NULL,
  `value`     BIGINT NOT NULL,
  `points`    TINYINT UNSIGNED NOT NULL,
  PRIMARY KEY (`run_id`,`metric_id`,`place`),
  KEY `idx_award_player` (`run_id`,`uuid`),
  KEY `fk_ma_metric` (`metric_id`),
  CONSTRAINT `fk_ma_run`    FOREIGN KEY (`run_id`) REFERENCES `import_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ma_metric` FOREIGN KEY (`metric_id`) REFERENCES `metric_def` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ============================================================================
-- Views (current snapshot via site_state.active_run_id)
-- Use SQL SECURITY INVOKER so you don't get stuck on DEFINER user mismatches.
-- ============================================================================

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `v_player_profile` AS
SELECT p.*
FROM `player_profile` p
JOIN `site_state` s ON s.id=1 AND p.run_id = s.active_run_id;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `v_player_stats` AS
SELECT ps.*
FROM `player_stats` ps
JOIN `site_state` s ON s.id=1 AND ps.run_id = s.active_run_id;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `v_metric_value` AS
SELECT mv.*
FROM `metric_value` mv
JOIN `site_state` s ON s.id=1 AND mv.run_id = s.active_run_id;

-- ============================================================================
-- Seeds: metric_def + metric_source (copied from your current dump)
-- ============================================================================
INSERT INTO `metric_def` (`id`, `label`, `category`, `unit`, `sort_order`, `enabled`, `divisor`, `decimals`) VALUES
('ancient_debris', 'Antiken Schrott abgebaut', 'Mining & Ressourcen', NULL, 11, 1, 1, 0),
('animals_bred', 'Tiere gezüchtet', 'Essen & Farmen', NULL, 70, 1, 1, 0),
('aviate_one_cm', 'Strecke geflogen', 'Bewegung & Reisen', 'km', 51, 1, 100000, 2),
('bamboo', 'Bambus geerntet', 'Essen & Farmen', NULL, 10, 1, 1, 0),
('blaze', 'Lohen getötet', 'Kampf & Kreaturen', NULL, 45, 1, 1, 0),
('boat_one_cm', 'Im Boot gefahrene Strecke', 'Bewegung & Reisen', 'km', 16, 1, 100000, 2),
('bow', 'Pfeile geschossen', 'Kampf & Kreaturen', NULL, 31, 1, 1, 0),
('bread', 'Brote gebacken', 'Essen & Farmen', NULL, 48, 1, 1, 0),
('cake', 'Kuchenstücke gegessen', 'Essen & Farmen', NULL, 9, 1, 1, 0),
('chorus_flower', 'Chorusblüten gepflanzt', 'Bauen & Technik', NULL, 37, 1, 1, 0),
('climb_one_cm', 'Gekletterte Strecke', 'Bewegung & Reisen', 'km', 15, 1, 100000, 2),
('compass', 'Kompasse hergestellt', 'Alltag & Handel', NULL, 59, 1, 1, 0),
('cooked_beef', 'Fleisch gegessen', 'Essen & Farmen', NULL, 46, 1, 1, 0),
('cookie', 'Kekse gegessen', 'Essen & Farmen', NULL, 55, 1, 1, 0),
('copper_ore', 'Kupfererz abgebaut', 'Mining & Ressourcen', NULL, 23, 1, 1, 0),
('cow', 'Kühe getötet', 'Kampf & Kreaturen', NULL, 54, 1, 1, 0),
('craft_beacon', 'Beacons hergestellt', 'Bauen & Technik', NULL, 12, 1, 1, 0),
('creeper', 'Creeper getötet', 'Kampf & Kreaturen', NULL, 36, 1, 1, 0),
('crouch_one_cm', 'Geschlichene Strecke', 'Bewegung & Reisen', 'km', 17, 1, 100000, 2),
('damage_absorbed', 'Schaden aufgenommen', 'Kampf & Kreaturen', NULL, 35, 1, 1, 0),
('damage_dealt', 'Schaden verursacht', 'Kampf & Kreaturen', NULL, 29, 1, 1, 0),
('deaths', 'Tode', 'Aktivität', NULL, 2, 1, 1, 0),
('diamond_ore', 'Diamanterz abgebaut', 'Mining & Ressourcen', NULL, 6, 1, 1, 0),
('dirt', 'Erde platziert', 'Bauen & Technik', NULL, 22, 1, 1, 0),
('distance', 'Zurückgelegte Strecke', 'Bewegung & Reisen', 'km', 4, 1, 100000, 2),
('dolphin', 'Delfine getötet', 'Kampf & Kreaturen', NULL, 38, 1, 1, 0),
('drink_milk', 'Milch getrunken', 'Alltag & Handel', NULL, 20, 1, 1, 0),
('drop', 'Fallengelassene Gegenstände', 'Alltag & Handel', NULL, 18, 1, 1, 0),
('dry_sponge', 'Schwämme getrocknet', 'Bauen & Technik', NULL, 19, 1, 1, 0),
('egg', 'Eier geworfen', 'Essen & Farmen', NULL, 44, 1, 1, 0),
('emerald_ore', 'Smaragderz abgebaut', 'Mining & Ressourcen', NULL, 28, 1, 1, 0),
('enchant_item', 'Items verzaubert', 'Alltag & Handel', NULL, 72, 1, 1, 0),
('ender_dragon', 'Enderdrachen getötet', 'Kampf & Kreaturen', NULL, 40, 1, 1, 0),
('firework_rocket', 'Feuerwerke gezündet', 'Spaß & Events', NULL, 50, 1, 1, 0),
('fished', 'Gefischte Fische', 'Essen & Farmen', NULL, 14, 1, 1, 0),
('goat_horn', 'Bockshörner geblasen', 'Spaß & Events', NULL, 47, 1, 1, 0),
('golderz_ore', 'Golderz abgebaut', 'Mining & Ressourcen', NULL, 52, 1, 1, 0),
('hopper', 'Trichter platziert', 'Bauen & Technik', NULL, 49, 1, 1, 0),
('horse_one_cm', 'Strecke geritten', 'Bewegung & Reisen', 'km', 63, 1, 100000, 2),
('hours', 'Spielstunden', 'Aktivität', 'h', 1, 1, 72000, 2),
('ice', 'Eis zerstört', 'Mining & Ressourcen', NULL, 42, 1, 1, 0),
('interact_with_anvil', 'Amboss benutzt', 'Bauen & Technik', NULL, 73, 1, 1, 0),
('jump', 'Sprünge', 'Bewegung & Reisen', NULL, 5, 1, 1, 0),
('kills', 'Spieler-Kills', 'Kampf & Kreaturen', NULL, 3, 1, 1, 0),
('king', 'Server-König', 'Bestenliste', 'Punkte', 0, 1, 1, 0),
('lodestone', 'Leitsteine platziert', 'Bauen & Technik', NULL, 56, 1, 1, 0),
('minecart_one_cm', 'Im Minecart gefahrene Strecke', 'Bewegung & Reisen', 'km', 68, 1, 100000, 1),
('mob_kills', 'Mobs getötet', 'Kampf & Kreaturen', NULL, 7, 1, 1, 0),
('open_chest', 'Truhen geöffnet', 'Alltag & Handel', NULL, 21, 1, 1, 0),
('phantom', 'Phantome getötet', 'Kampf & Kreaturen', NULL, 61, 1, 1, 0),
('polar_bear', 'Eisbären getötet', 'Kampf & Kreaturen', NULL, 41, 1, 1, 0),
('potion', 'Tränke getrunken', 'Alltag & Handel', NULL, 24, 1, 1, 0),
('raid_trigger', 'Überfälle ausgelöst', 'Kampf & Kreaturen', NULL, 34, 1, 1, 0),
('raid_win', 'Überfälle gewonnen', 'Kampf & Kreaturen', NULL, 71, 1, 1, 0),
('rail', 'Schienen platziert', 'Bauen & Technik', NULL, 39, 1, 1, 0),
('resin_block', 'Harzblöcke abgebaut', 'Mining & Ressourcen', NULL, 13, 1, 1, 0),
('scaffolding', 'Gerüste platziert', 'Bauen & Technik', NULL, 30, 1, 1, 0),
('sheep', 'Schafe getötet', 'Kampf & Kreaturen', NULL, 33, 1, 1, 0),
('shulker', 'Shulker getötet', 'Kampf & Kreaturen', NULL, 32, 1, 1, 0),
('silverfish', 'Silberfische getötet', 'Kampf & Kreaturen', NULL, 58, 1, 1, 0),
('sleep_in_bed', 'Im Bett geschlafen', 'Aktivität', NULL, 64, 1, 1, 0),
('spider', 'Spinnen getötet', 'Kampf & Kreaturen', NULL, 25, 1, 1, 0),
('sprint_one_cm', 'Strecke gerannt', 'Bewegung & Reisen', 'km', 57, 1, 100000, 2),
('strider_one_cm', 'Auf Strider gerittene Strecke', 'Bewegung & Reisen', 'km', 69, 1, 100000, 1),
('string', 'Spinnennetze entfernt', 'Bauen & Technik', NULL, 26, 1, 1, 0),
('sweet_berries', 'Beeren gesammelt', 'Essen & Farmen', NULL, 27, 1, 1, 0),
('swim_one_cm', 'Strecke geschwommen', 'Bewegung & Reisen', 'km', 67, 1, 100000, 1),
('time_since_death_h', 'Überlebenszeit seit letztem Tod', 'Aktivität', 'h', 65, 1, 72000, 1),
('time_since_rest_h', 'Wachzeit seit letztem Schlaf', 'Aktivität', 'h', 66, 1, 72000, 1),
('torch', 'Fackeln platziert', 'Bauen & Technik', NULL, 43, 1, 1, 0),
('totem_of_undying', 'Totems benutzt', 'Kampf & Kreaturen', NULL, 8, 1, 1, 0),
('traded_with_villager', 'Handel mit Dorfbewohnern', 'Alltag & Handel', NULL, 53, 1, 1, 0),
('warden', 'Tode durch Warden', 'Kampf & Kreaturen', NULL, 62, 1, 1, 0),
('zombified_piglin', 'Piglins getötet', 'Kampf & Kreaturen', NULL, 60, 1, 1, 0);

INSERT INTO `metric_source` (`metric_id`, `section`, `mc_key`, `weight`) VALUES
('ancient_debris', 'minecraft:mined', 'minecraft:ancient_debris', 1),
('animals_bred', 'minecraft:custom', 'minecraft:animals_bred', 1),
('aviate_one_cm', 'minecraft:custom', 'minecraft:aviate_one_cm', 1),
('bamboo', 'minecraft:mined', 'minecraft:bamboo', 1),
('blaze', 'minecraft:killed', 'minecraft:blaze', 1),
('boat_one_cm', 'minecraft:custom', 'minecraft:boat_one_cm', 1),
('bow', 'minecraft:used', 'minecraft:bow', 1),
('bread', 'minecraft:crafted', 'minecraft:bread', 1),
('cake', 'minecraft:custom', 'minecraft:eat_cake_slice', 1),
('chorus_flower', 'minecraft:used', 'minecraft:chorus_flower', 1),
('climb_one_cm', 'minecraft:custom', 'minecraft:climb_one_cm', 1),
('compass', 'minecraft:crafted', 'minecraft:compass', 1),
('cooked_beef', 'minecraft:used', 'minecraft:cooked_beef', 1),
('cookie', 'minecraft:used', 'minecraft:cookie', 1),
('copper_ore', 'minecraft:mined', 'minecraft:copper_ore', 1),
('cow', 'minecraft:killed', 'minecraft:cow', 1),
('craft_beacon', 'minecraft:crafted', 'minecraft:beacon', 1),
('creeper', 'minecraft:killed', 'minecraft:creeper', 1),
('crouch_one_cm', 'minecraft:custom', 'minecraft:crouch_one_cm', 1),
('damage_absorbed', 'minecraft:custom', 'minecraft:damage_absorbed', 1),
('damage_dealt', 'minecraft:custom', 'minecraft:damage_dealt', 1),
('deaths', 'minecraft:custom', 'minecraft:deaths', 1),
('diamond_ore', 'minecraft:mined', 'minecraft:deepslate_diamond_ore', 1),
('diamond_ore', 'minecraft:mined', 'minecraft:diamond_ore', 1),
('dirt', 'minecraft:used', 'minecraft:dirt', 1),
('distance', 'minecraft:custom', 'minecraft:walk_one_cm', 1),
('dolphin', 'minecraft:killed', 'minecraft:dolphin', 1),
('drink_milk', 'minecraft:used', 'minecraft:milk_bucket', 1),
('drop', 'minecraft:custom', 'minecraft:drop', 1),
('dry_sponge', 'minecraft:mined', 'minecraft:sponge', 1),
('egg', 'minecraft:used', 'minecraft:egg', 1),
('emerald_ore', 'minecraft:mined', 'minecraft:emerald_ore', 1),
('enchant_item', 'minecraft:custom', 'minecraft:enchant_item', 1),
('ender_dragon', 'minecraft:killed', 'minecraft:ender_dragon', 1),
('firework_rocket', 'minecraft:used', 'minecraft:firework_rocket', 1),
('fished', 'minecraft:custom', 'minecraft:fish_caught', 1),
('goat_horn', 'minecraft:used', 'minecraft:goat_horn', 1),
('golderz_ore', 'minecraft:mined', 'minecraft:deepslate_gold_ore', 1),
('golderz_ore', 'minecraft:mined', 'minecraft:gold_ore', 1),
('golderz_ore', 'minecraft:mined', 'minecraft:nether_gold_ore', 1),
('hopper', 'minecraft:used', 'minecraft:hopper', 1),
('horse_one_cm', 'minecraft:custom', 'minecraft:horse_one_cm', 1),
('hours', 'minecraft:custom', 'minecraft:play_time', 1),
('ice', 'minecraft:mined', 'minecraft:ice', 1),
('interact_with_anvil', 'minecraft:custom', 'minecraft:interact_with_anvil', 1),
('jump', 'minecraft:custom', 'minecraft:jump', 1),
('kills', 'minecraft:custom', 'minecraft:player_kills', 1),
('lodestone', 'minecraft:used', 'minecraft:lodestone', 1),
('minecart_one_cm', 'minecraft:custom', 'minecraft:minecart_one_cm', 1),
('mob_kills', 'minecraft:custom', 'minecraft:mob_kills', 1),
('open_chest', 'minecraft:custom', 'minecraft:open_chest', 1),
('phantom', 'minecraft:killed', 'minecraft:phantom', 1),
('polar_bear', 'minecraft:killed', 'minecraft:polar_bear', 1),
('potion', 'minecraft:used', 'minecraft:potion', 1),
('raid_trigger', 'minecraft:custom', 'minecraft:raid_trigger', 1),
('raid_win', 'minecraft:custom', 'minecraft:raid_win', 1),
('rail', 'minecraft:used', 'minecraft:rail', 1),
('resin_block', 'minecraft:mined', 'minecraft:resin_block', 1),
('scaffolding', 'minecraft:used', 'minecraft:scaffolding', 1),
('sheep', 'minecraft:killed', 'minecraft:sheep', 1),
('shulker', 'minecraft:killed', 'minecraft:shulker', 1),
('silverfish', 'minecraft:killed', 'minecraft:silverfish', 1),
('sleep_in_bed', 'minecraft:custom', 'minecraft:sleep_in_bed', 1),
('spider', 'minecraft:killed', 'minecraft:spider', 1),
('sprint_one_cm', 'minecraft:custom', 'minecraft:sprint_one_cm', 1),
('strider_one_cm', 'minecraft:custom', 'minecraft:strider_one_cm', 1),
('string', 'minecraft:picked_up', 'minecraft:string', 1),
('sweet_berries', 'minecraft:picked_up', 'minecraft:sweet_berries', 1),
('swim_one_cm', 'minecraft:custom', 'minecraft:swim_one_cm', 1),
('time_since_death_h', 'minecraft:custom', 'minecraft:time_since_death', 1),
('time_since_rest_h', 'minecraft:custom', 'minecraft:time_since_rest', 1),
('torch', 'minecraft:used', 'minecraft:torch', 1),
('totem_of_undying', 'minecraft:used', 'minecraft:totem_of_undying', 1),
('traded_with_villager', 'minecraft:custom', 'minecraft:traded_with_villager', 1),
('warden', 'minecraft:killed_by', 'minecraft:warden', 1),
('zombified_piglin', 'minecraft:killed', 'minecraft:zombified_piglin', 1);

COMMIT;
