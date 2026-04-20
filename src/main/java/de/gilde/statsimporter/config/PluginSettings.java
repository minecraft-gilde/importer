package de.gilde.statsimporter.config;

import java.util.List;
import java.util.UUID;

public record PluginSettings(
        ImportSettings importSettings,
        DatabaseSettings databaseSettings,
        BootstrapSettings bootstrapSettings
) {

    public record ImportSettings(
            boolean enabled,
            long intervalSeconds,
            String statsDirectory,
            String usercachePath,
            int minPlayTicks,
            int workerThreads,
            int maxInflightCalculations,
            int flushSeen,
            int flushProfiles,
            int flushChanged,
            boolean ignoreHashOnTimer,
            String dbLockName,
            int dbLockTimeoutSeconds,
            boolean kingEnabled,
            String kingMetricId,
            List<Integer> kingPoints,
            List<UUID> excludedUuids
    ) {
    }

    public record DatabaseSettings(
            String host,
            int port,
            String name,
            String user,
            String password,
            int poolMaxSize,
            long connectionTimeoutMs
    ) {
    }

    public record BootstrapSettings(
            boolean autoSchema,
            boolean verifySchema,
            boolean seedOnMissingSchema,
            boolean seedIfMetricDefEmpty,
            String seedFile
    ) {
    }
}
