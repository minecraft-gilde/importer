CREATE TABLE IF NOT EXISTS import_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  generated_at DATETIME NOT NULL,
  status ENUM('loading','active','failed') NOT NULL DEFAULT 'loading',
  note VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_status_time (status, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS site_state (
  id TINYINT UNSIGNED NOT NULL,
  active_run_id BIGINT UNSIGNED DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY fk_active_run (active_run_id),
  CONSTRAINT fk_active_run FOREIGN KEY (active_run_id) REFERENCES import_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO site_state (id, active_run_id)
VALUES (1, NULL)
ON DUPLICATE KEY UPDATE id = VALUES(id);

CREATE TABLE IF NOT EXISTS player_profile (
  run_id BIGINT UNSIGNED NOT NULL,
  uuid BINARY(16) NOT NULL,
  name VARCHAR(16) NOT NULL,
  name_lc VARCHAR(16) NOT NULL,
  name_source VARCHAR(16) NOT NULL DEFAULT 'unknown',
  name_checked_at DATETIME DEFAULT NULL,
  last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, uuid),
  KEY idx_name_search (run_id, name_lc, uuid),
  KEY idx_name_refresh (run_id, name_source, name_checked_at),
  KEY idx_last_seen_run (run_id, last_seen),
  CONSTRAINT fk_pp_run FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS player_stats (
  run_id BIGINT UNSIGNED NOT NULL,
  uuid BINARY(16) NOT NULL,
  stats_gzip MEDIUMBLOB NOT NULL,
  stats_sha1 BINARY(20) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, uuid),
  KEY idx_hash (run_id, stats_sha1),
  CONSTRAINT fk_ps_run FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS metric_def (
  id VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  category VARCHAR(64) NOT NULL DEFAULT 'Allgemein',
  unit VARCHAR(32) DEFAULT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  divisor BIGINT UNSIGNED NOT NULL DEFAULT 1,
  decimals TINYINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS metric_source (
  metric_id VARCHAR(64) NOT NULL,
  section VARCHAR(64) NOT NULL,
  mc_key VARCHAR(128) NOT NULL,
  weight INT NOT NULL DEFAULT 1,
  PRIMARY KEY (metric_id, section, mc_key),
  CONSTRAINT fk_ms_metric FOREIGN KEY (metric_id) REFERENCES metric_def (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS metric_value (
  run_id BIGINT UNSIGNED NOT NULL,
  metric_id VARCHAR(64) NOT NULL,
  uuid BINARY(16) NOT NULL,
  value BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id, metric_id, uuid),
  KEY idx_metric_sort (run_id, metric_id, value DESC, uuid),
  KEY idx_player_metrics (run_id, uuid, metric_id),
  KEY fk_mv_metric (metric_id),
  CONSTRAINT fk_mv_run FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE CASCADE,
  CONSTRAINT fk_mv_metric FOREIGN KEY (metric_id) REFERENCES metric_def (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS metric_award (
  run_id BIGINT UNSIGNED NOT NULL,
  metric_id VARCHAR(64) NOT NULL,
  place TINYINT UNSIGNED NOT NULL,
  uuid BINARY(16) NOT NULL,
  value BIGINT NOT NULL,
  points TINYINT UNSIGNED NOT NULL,
  PRIMARY KEY (run_id, metric_id, place),
  KEY idx_award_player (run_id, uuid),
  KEY fk_ma_metric (metric_id),
  CONSTRAINT fk_ma_run FOREIGN KEY (run_id) REFERENCES import_run (id) ON DELETE CASCADE,
  CONSTRAINT fk_ma_metric FOREIGN KEY (metric_id) REFERENCES metric_def (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW v_player_profile AS
SELECT p.*
FROM player_profile p
JOIN site_state s ON s.id = 1 AND p.run_id = s.active_run_id;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW v_player_stats AS
SELECT ps.*
FROM player_stats ps
JOIN site_state s ON s.id = 1 AND ps.run_id = s.active_run_id;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW v_metric_value AS
SELECT mv.*
FROM metric_value mv
JOIN site_state s ON s.id = 1 AND mv.run_id = s.active_run_id;

