package de.gilde.statsimporter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void loadsStrictParseErrorDefaultAndSeedSyncDefault() {
        PluginSettings settings = ConfigLoader.load(minimalConfig());

        assertEquals(0, settings.importSettings().safetyMaxParseErrors());
        assertTrue(settings.bootstrapSettings().syncSeeds());
    }

    @Test
    void clampsParseErrorLimitAtDisabledSentinel() {
        YamlConfiguration config = minimalConfig();
        config.set("import.safety.max-parse-errors", -99);

        PluginSettings settings = ConfigLoader.load(config);

        assertEquals(-1, settings.importSettings().safetyMaxParseErrors());
    }

    @Test
    void normalizesKingPointsToThreeSlots() {
        YamlConfiguration config = minimalConfig();
        config.set("import.king-points", java.util.List.of(9));

        PluginSettings settings = ConfigLoader.load(config);

        assertEquals(java.util.List.of(9, 0, 0), settings.importSettings().kingPoints());
    }

    private YamlConfiguration minimalConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.host", "127.0.0.1");
        config.set("database.name", "stats");
        config.set("database.user", "stats_user");
        return config;
    }
}
