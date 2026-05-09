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
            String bannedPlayersPath,
            boolean worldAgeEnabled,
            String worldAgeWorld,
            int minPlayTicks,
            int safetyMinProcessedFiles,
            int safetyMinKeptPlayers,
            int safetyMaxParseErrors,
            int workerThreads,
            int maxInflightCalculations,
            int flushProfiles,
            int flushChanged,
            boolean ignoreHashOnTimer,
            int retentionKeepRuns,
            String dbLockName,
            int dbLockTimeoutSeconds,
            boolean kingEnabled,
            String kingMetricId,
            List<Integer> kingPoints,
            List<UUID> excludedUuids,
            boolean nameResolverEnabled,
            int nameResolverMaxPerRun,
            boolean nameResolverAfterImportEnabled,
            int nameResolverAfterImportMaxPerRun,
            boolean nameResolverMaintenanceEnabled,
            long nameResolverMaintenanceIntervalSeconds,
            int nameResolverMaintenanceMaxPerRun,
            int nameResolverRefreshDays,
            int nameResolverSleepMs,
            int nameResolverConnectTimeoutMs,
            int nameResolverRequestTimeoutMs
    ) {
    }

    public record DatabaseSettings(
            String host,
            int port,
            String name,
            String user,
            String password,
            int poolMaxSize,
            long connectionTimeoutMs,
            long maxLifetimeMs
    ) {
    }

    public record BootstrapSettings(
            boolean autoSchema,
            boolean verifySchema,
            boolean seedOnMissingSchema,
            boolean seedIfMetricDefEmpty,
            boolean syncSeeds,
            String seedFile
    ) {
    }
}
