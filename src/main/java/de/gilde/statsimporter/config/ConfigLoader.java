package de.gilde.statsimporter.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginSettings load(FileConfiguration config) {
        PluginSettings.ImportSettings importSettings = new PluginSettings.ImportSettings(
                config.getBoolean("import.enabled", true),
                Math.max(1L, config.getLong("import.interval-seconds", 60L)),
                config.getString("import.stats-dir", "C:/minecraft/world/stats"),
                config.getString("import.usercache-path", "C:/minecraft/usercache.json"),
                Math.max(0, config.getInt("import.min-play-ticks", 72000)),
                Math.max(1, config.getInt("import.worker-threads", 6)),
                Math.max(10, config.getInt("import.max-inflight-calculations", 3000)),
                Math.max(1, config.getInt("import.flush-seen", 2000)),
                Math.max(1, config.getInt("import.flush-profiles", 2000)),
                Math.max(1, config.getInt("import.flush-changed", 800)),
                config.getBoolean("import.ignore-hash-on-timer", false),
                config.getString("import.db-lock-name", "mc_stats_import"),
                Math.max(0, config.getInt("import.db-lock-timeout-seconds", 5)),
                config.getBoolean("import.king-enabled", true),
                config.getString("import.king-metric-id", "king"),
                normalizePoints(config.getIntegerList("import.king-points")),
                parseExcludedUuids(config.getStringList("import.exclude-uuids"))
        );

        String dbHost = mustNotBeBlank(config.getString("database.host", ""), "database.host");
        String dbName = mustNotBeBlank(config.getString("database.name", ""), "database.name");
        String dbUser = mustNotBeBlank(config.getString("database.user", ""), "database.user");
        String dbPassword = config.getString("database.password", "");

        PluginSettings.DatabaseSettings databaseSettings = new PluginSettings.DatabaseSettings(
                dbHost,
                Math.max(1, config.getInt("database.port", 3306)),
                dbName,
                dbUser,
                dbPassword == null ? "" : dbPassword,
                Math.max(1, config.getInt("database.pool-max-size", 10)),
                Math.max(1000L, config.getLong("database.connection-timeout-ms", 10000L))
        );

        PluginSettings.BootstrapSettings bootstrapSettings = new PluginSettings.BootstrapSettings(
                config.getBoolean("bootstrap.auto-schema", true),
                config.getBoolean("bootstrap.verify-schema", true),
                config.getBoolean("bootstrap.seed-on-missing-schema", true),
                config.getBoolean("bootstrap.seed-if-metric-def-empty", true),
                config.getString("bootstrap.seed-file", "metric-seeds.yml")
        );

        return new PluginSettings(importSettings, databaseSettings, bootstrapSettings);
    }

    private static String mustNotBeBlank(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing config value: " + key);
        }
        return value.trim();
    }

    private static List<Integer> normalizePoints(List<Integer> configured) {
        List<Integer> points = new ArrayList<>();
        if (configured != null) {
            for (Integer point : configured) {
                if (point == null) {
                    continue;
                }
                points.add(Math.max(0, point));
            }
        }
        if (points.isEmpty()) {
            points.add(5);
            points.add(3);
            points.add(1);
        }
        // Always expose exactly top-3 slots so downstream ranking logic can index safely.
        while (points.size() < 3) {
            points.add(0);
        }
        return Collections.unmodifiableList(points);
    }

    private static List<UUID> parseExcludedUuids(List<String> rawValues) {
        List<UUID> parsed = new ArrayList<>();
        if (rawValues == null) {
            return Collections.emptyList();
        }
        for (String value : rawValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(parseUuidFlexible(value));
            } catch (IllegalArgumentException ignored) {
                // Ignore bad entries instead of failing startup for one malformed config value.
            }
        }
        return Collections.unmodifiableList(parsed);
    }

    private static UUID parseUuidFlexible(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() == 32 && !normalized.contains("-")) {
            normalized = normalized.substring(0, 8) + "-"
                    + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        return UUID.fromString(normalized);
    }
}
