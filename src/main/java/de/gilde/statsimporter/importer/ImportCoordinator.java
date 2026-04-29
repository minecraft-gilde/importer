package de.gilde.statsimporter.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.gilde.statsimporter.config.PluginSettings;
import de.gilde.statsimporter.model.ComputationResult;
import de.gilde.statsimporter.model.ImportSummary;
import de.gilde.statsimporter.model.MetricSource;
import de.gilde.statsimporter.model.MetricValueRow;
import de.gilde.statsimporter.model.PlayerProfileMeta;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.plugin.java.JavaPlugin;

public final class ImportCoordinator implements AutoCloseable {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String NAME_RESOLVER_USER_AGENT = "statsimporter-name-resolver/1.0";
    private static final int NAME_RESOLVER_BATCH_FLUSH_ROWS = 25;
    private static final Duration NAME_RESOLVER_BATCH_FLUSH_INTERVAL = Duration.ofSeconds(20);
    private static final DateTimeFormatter MINECRAFT_BAN_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
    private static final int KNOWN_NAME_PRIORITY_UNKNOWN = 0;
    private static final int KNOWN_NAME_PRIORITY_FALLBACK = 1;
    private static final int KNOWN_NAME_PRIORITY_BANLIST = 2;
    private static final int KNOWN_NAME_PRIORITY_USERCACHE = 3;
    private static final int KNOWN_NAME_PRIORITY_MOJANG = 4;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PluginSettings.ImportSettings settings;
    private final javax.sql.DataSource dataSource;
    private final ExecutorService importExecutor;
    private final ExecutorService calculatorPool;
    private final StatsCalculator calculator;
    private final ObjectMapper objectMapper;
    private final HttpClient nameResolverHttpClient;
    private final AtomicBoolean running;
    private final AtomicBoolean importQueuedOrRunning;
    private final AtomicBoolean shuttingDown;
    private final NameResolveQueue nameResolveQueue;

    private volatile ImportSummary lastSummary;

    public ImportCoordinator(JavaPlugin plugin, PluginSettings settings, javax.sql.DataSource dataSource) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings.importSettings();
        this.dataSource = dataSource;
        this.importExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "stats-import-main");
            thread.setDaemon(true);
            return thread;
        });
        this.calculatorPool = Executors.newFixedThreadPool(settings.importSettings().workerThreads(), r -> {
            Thread thread = new Thread(r, "stats-import-calc");
            thread.setDaemon(true);
            return thread;
        });
        this.calculator = new StatsCalculator();
        this.objectMapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .build();
        this.nameResolverHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.importSettings().nameResolverConnectTimeoutMs()))
                .build();
        this.running = new AtomicBoolean(false);
        this.importQueuedOrRunning = new AtomicBoolean(false);
        this.shuttingDown = new AtomicBoolean(false);
        this.nameResolveQueue = new NameResolveQueue();
        this.lastSummary = ImportSummary.neverRan();
    }

    public boolean triggerImport(String reason, boolean ignoreHashOverride) {
        return triggerImport(reason, ignoreHashOverride, false);
    }

    public boolean triggerImport(String reason, boolean ignoreHashOverride, boolean dryRun) {
        if (shuttingDown.get()) {
            logger.fine("Skip import trigger during shutdown. reason=" + reason);
            return false;
        }
        if (!importQueuedOrRunning.compareAndSet(false, true)) {
            logger.fine("Skip import trigger because another run is active. reason=" + reason);
            return false;
        }
        CompletableFuture.runAsync(() -> runImport(reason, ignoreHashOverride, dryRun), importExecutor);
        return true;
    }

    public boolean triggerNameResolve(String reason) {
        return triggerNameResolve(reason, null);
    }

    public boolean triggerNameResolve(String reason, Integer maxPerRunOverride) {
        if (shuttingDown.get()) {
            logger.fine("Skip name-resolve trigger during shutdown. reason=" + reason);
            return false;
        }
        boolean shouldSubmit = nameResolveQueue.offer(reason, maxPerRunOverride);
        if (shouldSubmit) {
            CompletableFuture.runAsync(this::runQueuedNameResolve, importExecutor);
        } else {
            logger.fine("Merged name-resolve trigger into queued run. reason=" + reason);
        }
        return true;
    }

    public boolean isRunning() {
        return running.get() || importQueuedOrRunning.get() || nameResolveQueue.hasQueuedRequest();
    }

    public ImportSummary lastSummary() {
        return lastSummary;
    }

    @Override
    public void close() {
        shuttingDown.set(true);
        importExecutor.shutdownNow();
        calculatorPool.shutdownNow();
        try {
            importExecutor.awaitTermination(10, TimeUnit.SECONDS);
            calculatorPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runImport(String reason, boolean ignoreHashOverride, boolean dryRun) {
        running.set(true);
        Instant startedAt = Instant.now();
        String message = dryRun ? "Dry-run OK (no DB writes)" : "OK";
        boolean success = false;
        int processed = 0;
        int kept = 0;
        int changed = 0;
        int parseErrors = 0;
        boolean lockAcquired = false;
        String resolverNote = "";
        String banNote = "";
        String knownNote = "";
        String retentionNote = "";
        Long candidateRunId = null;

        Path statsDir = resolveStatsDir();
        Path usercachePath = resolveUsercachePath();
        Path bannedPlayersPath = resolveBannedPlayersPath();

        try {
            if (!Files.isDirectory(statsDir)) {
                throw new IllegalStateException("stats-dir not found: " + statsDir);
            }
            logger.info("Resolved paths: stats-dir=" + statsDir
                    + ", usercache-path=" + usercachePath
                    + ", banned-players-path=" + bannedPlayersPath);

            Map<UUID, String> usercacheNames = loadUsercache(usercachePath);
            logger.info("Loaded " + usercacheNames.size() + " names from usercache.");
            BanFileLoadResult banFile = loadBannedPlayers(bannedPlayersPath);
            if (banFile.syncable()) {
                logger.info("Loaded " + banFile.entries().size() + " ban entries from banned-players.json.");
            } else {
                logger.info("Ban import skipped: " + banFile.note());
            }

            try (Connection connection = dataSource.getConnection()) {
                try {
                    // Named DB lock keeps parallel plugin instances/nodes from writing the same run simultaneously.
                    lockAcquired = acquireDbLock(connection, settings.dbLockName(), settings.dbLockTimeoutSeconds());
                    if (!lockAcquired) {
                        message = "Could not acquire DB lock '" + settings.dbLockName() + "'";
                        logger.warning(message);
                        return;
                    }

                    Long previousRunId = findActiveRunId(connection);
                    long runId;
                    if (dryRun) {
                        runId = requireActiveRunId(connection);
                        logger.info("Using run_id=" + runId + " (dry-run/read-only)");
                    } else {
                        runId = createLoadingRun(connection);
                        candidateRunId = runId;
                        logger.info("Using run_id=" + runId + " (loading snapshot), previous="
                                + (previousRunId == null ? "none" : previousRunId));
                    }

                    Map<String, List<MetricSource>> metricSources = loadMetricSources(connection);
                    if (metricSources.isEmpty()) {
                        throw new IllegalStateException("No enabled metrics found. Seed metric_def + metric_source first.");
                    }
                    logger.info("Loaded " + metricSources.size() + " metrics with sources.");

                    long comparisonRunId = dryRun ? runId : (previousRunId == null ? runId : previousRunId);
                    Map<UUID, byte[]> existingHashes = loadExistingHashes(connection, comparisonRunId);
                    boolean hasNameSource = tableHasColumn(connection, "player_profile", "name_source");
                    boolean hasNameCheckedAt = tableHasColumn(connection, "player_profile", "name_checked_at");
                    Map<UUID, PlayerProfileMeta> existingProfiles = loadExistingProfiles(
                            connection,
                            comparisonRunId,
                            hasNameSource,
                            hasNameCheckedAt
                    );

                    Set<UUID> excluded = new HashSet<>(settings.excludedUuids());
                    // Buffers batch DB writes to reduce round-trips while still committing regularly.
                    List<ProfileRow> profileBuffer = new ArrayList<>();
                    List<UUID> changedUuidsBuffer = new ArrayList<>();
                    List<UUID> unchangedUuidsBuffer = new ArrayList<>();
                    List<UUID> carryOverUuidsBuffer = new ArrayList<>();
                    List<StatsRow> statsRowsBuffer = new ArrayList<>();
                    List<MetricValueRow> metricRowsBuffer = new ArrayList<>();
                    Set<UUID> knownStatsSourceUuids = new HashSet<>();
                    Set<UUID> statsIncludedUuids = new HashSet<>();

                    // Backpressure guard: cap submitted calculator jobs so memory usage stays bounded.
                    int maxInflight = Math.max(settings.workerThreads(), settings.maxInflightCalculations());
                    Semaphore inflightSemaphore = new Semaphore(maxInflight);
                    ExecutorCompletionService<ComputationResult> completionService = new ExecutorCompletionService<>(calculatorPool);
                    int submitted = 0;
                    int completed = 0;

                    try (DirectoryStream<Path> files = Files.newDirectoryStream(statsDir, "*.json")) {
                        for (Path file : files) {
                            processed++;
                            UUID uuid = parseUuidFromStatsFilename(file);
                            if (uuid == null) {
                                continue;
                            }
                            if (excluded.contains(uuid)) {
                                continue;
                            }
                            knownStatsSourceUuids.add(uuid);

                            Map<String, Object> root;
                            try {
                                root = objectMapper.readValue(file.toFile(), MAP_TYPE);
                            } catch (Exception ex) {
                                parseErrors++;
                                if (!dryRun) {
                                    // Keep previous snapshot rows for unreadable files until parsing succeeds again.
                                    if (previousRunId != null) {
                                        carryOverUuidsBuffer.add(uuid);
                                        if (carryOverUuidsBuffer.size() >= settings.flushChanged()) {
                                            copyPreviousSnapshotBatch(
                                                    connection,
                                                    previousRunId,
                                                    runId,
                                                    carryOverUuidsBuffer,
                                                    hasNameSource,
                                                    hasNameCheckedAt
                                            );
                                        }
                                    }
                                }
                                logger.log(Level.WARNING, "Cannot parse stats file " + file.getFileName(), ex);
                                validateParseErrorSafety(parseErrors);
                                continue;
                            }

                            Map<String, Object> stats = extractStatsMap(root);
                            // Legacy banner keys are filtered so removed/renamed blocks do not skew totals.
                            stripWallBanners(stats);

                            long playTicks = nestedLong(stats, "minecraft:custom", "minecraft:play_time");
                            if (playTicks < settings.minPlayTicks()) {
                                continue;
                            }

                            statsIncludedUuids.add(uuid);
                            kept++;
                            if (!dryRun) {
                                ProfileRow profileRow = buildProfileRow(
                                        runId,
                                        uuid,
                                        usercacheNames,
                                        existingProfiles,
                                        hasNameSource,
                                        hasNameCheckedAt
                                );
                                profileBuffer.add(profileRow);
                                if (profileBuffer.size() >= settings.flushProfiles()) {
                                    flushProfiles(connection, profileBuffer, hasNameSource, hasNameCheckedAt);
                                }
                            }

                            byte[] canonicalStatsJson = objectMapper.writeValueAsBytes(stats);
                            byte[] sha1 = sha1(canonicalStatsJson);
                            boolean ignoreHash = ignoreHashOverride || ("timer".equals(reason) && settings.ignoreHashOnTimer());
                            // Unchanged players skip expensive recomputation; completed async tasks are still drained.
                            if (!ignoreHash && Arrays.equals(existingHashes.get(uuid), sha1)) {
                                if (!dryRun) {
                                    if (previousRunId != null) {
                                        unchangedUuidsBuffer.add(uuid);
                                        if (unchangedUuidsBuffer.size() >= settings.flushChanged()) {
                                            copyPreviousStatsAndMetricsBatch(connection, previousRunId, runId, unchangedUuidsBuffer);
                                        }
                                    }
                                    completed += drainReadyResults(connection, completionService, changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer, runId, false);
                                }
                                continue;
                            }

                            changed++;
                            existingHashes.put(uuid, sha1);

                            if (dryRun) {
                                calculator.compute(uuid, canonicalStatsJson, sha1, stats, metricSources);
                                continue;
                            }

                            // Acquire before submit to ensure the in-flight cap is respected.
                            inflightSemaphore.acquire();
                            submitted++;
                            Map<String, Object> statsForTask = stats;
                            completionService.submit(() -> {
                                try {
                                    return calculator.compute(uuid, canonicalStatsJson, sha1, statsForTask, metricSources);
                                } finally {
                                    inflightSemaphore.release();
                                }
                            });

                            completed += drainReadyResults(connection, completionService, changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer, runId, false);
                        }
                    }

                    if (!dryRun) {
                        while (completed < submitted) {
                            // Final blocking drain: wait until every submitted calculator result was persisted.
                            completed += drainReadyResults(connection, completionService, changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer, runId, true);
                        }

                        flushProfiles(connection, profileBuffer, hasNameSource, hasNameCheckedAt);
                        if (previousRunId != null) {
                            copyPreviousSnapshotBatch(
                                    connection,
                                    previousRunId,
                                    runId,
                                    carryOverUuidsBuffer,
                                    hasNameSource,
                                    hasNameCheckedAt
                            );
                            copyPreviousStatsAndMetricsBatch(connection, previousRunId, runId, unchangedUuidsBuffer);
                        }
                        flushChangedBatch(connection, runId, changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer);

                        validateInputSafety(processed, kept);

                        KnownSyncResult knownSyncResult = syncKnownPlayers(
                                connection,
                                knownStatsSourceUuids,
                                statsIncludedUuids,
                                usercacheNames,
                                banFile
                        );
                        knownNote = " | known " + knownSyncResult.note();

                        BanSyncResult banSyncResult = syncBans(connection, runId, banFile);
                        banNote = " | bans " + banSyncResult.note();

                        if (settings.kingEnabled()) {
                            recomputeKingPoints(connection, runId, new ArrayList<>(metricSources.keySet()));
                        }

                        if (settings.nameResolverEnabled() && settings.nameResolverAfterImportEnabled()) {
                            int maxAfterImport = settings.nameResolverAfterImportMaxPerRun();
                            triggerNameResolve("after-import:" + reason, maxAfterImport);
                            resolverNote = " | names queued max=" + maxAfterImport;
                        } else if (settings.nameResolverEnabled()) {
                            resolverNote = " | names after-import disabled";
                        }

                        publishRun(connection, runId);
                        candidateRunId = null;
                        try {
                            int deletedRuns = cleanupOldRuns(connection, runId);
                            if (deletedRuns > 0) {
                                retentionNote = " | retention deleted-runs=" + deletedRuns;
                            }
                        } catch (SQLException ex) {
                            logger.log(Level.WARNING, "Retention cleanup failed after snapshot publish.", ex);
                            retentionNote = " | retention cleanup failed: " + ex.getClass().getSimpleName();
                        }
                    } else {
                        validateInputSafety(processed, kept);
                        resolverNote = " | dry-run: skipped write/cleanup/king/name-resolver";
                        banNote = " | bans dry-run: parsed=" + banFile.entries().size() + ", syncable=" + banFile.syncable();
                        knownNote = " | known dry-run: stats-source=" + knownStatsSourceUuids.size()
                                + ", stats-included=" + statsIncludedUuids.size()
                                + ", usercache=" + usercacheNames.size()
                                + ", bans=" + banFile.entries().size();
                    }

                    if (!knownNote.isBlank()) {
                        message = message + knownNote;
                    }
                    if (!banNote.isBlank()) {
                        message = message + banNote;
                    }
                    if (!resolverNote.isBlank()) {
                        message = message + resolverNote;
                    }
                    if (!retentionNote.isBlank()) {
                        message = message + retentionNote;
                    }
                    if (parseErrors > 0) {
                        message = message + " | parse-errors=" + parseErrors;
                    }
                    success = true;
                } finally {
                    if (!success) {
                        try {
                            connection.rollback();
                        } catch (SQLException ex) {
                            logger.log(Level.FINE, "Rollback before lock release failed.", ex);
                        }
                    }
                    if (lockAcquired) {
                        try {
                            releaseDbLock(connection, settings.dbLockName());
                        } catch (SQLException ex) {
                            logger.log(Level.WARNING, "Could not release DB lock.", ex);
                        }
                        lockAcquired = false;
                    }
                }
            }
        } catch (Exception ex) {
            message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (candidateRunId != null) {
                markRunFailed(candidateRunId, message);
            }
            if (shuttingDown.get()) {
                logger.log(Level.INFO, "Import stopped due to plugin shutdown: " + message);
            } else {
                logger.log(Level.SEVERE, "Import failed", ex);
            }
        } finally {
            ImportSummary summary = new ImportSummary(
                    startedAt,
                    Instant.now(),
                    success,
                    reason,
                    message,
                    processed,
                    kept,
                    changed
            );
            lastSummary = summary;
            importQueuedOrRunning.set(false);
            running.set(false);
            logger.info("Import finished. success=" + success
                    + ", reason=" + reason
                    + ", processed=" + processed
                    + ", kept=" + kept
                    + ", changed=" + changed
                    + ", parseErrors=" + parseErrors
                    + ", duration=" + summary.durationSeconds() + "s");
        }
    }

    private void runQueuedNameResolve() {
        NameResolveQueue.NameResolveRequest request = nameResolveQueue.poll();
        if (request == null || shuttingDown.get()) {
            return;
        }
        running.set(true);
        runNameResolveOnly(request.reason(), request.maxPerRunOverride());
    }

    private void runNameResolveOnly(String reason, Integer maxPerRunOverride) {
        Instant startedAt = Instant.now();
        String message = "OK";
        boolean success = false;
        boolean lockAcquired = false;

        try (Connection connection = dataSource.getConnection()) {
            try {
                lockAcquired = acquireDbLock(connection, settings.dbLockName(), settings.dbLockTimeoutSeconds());
                if (!lockAcquired) {
                    message = "Could not acquire DB lock '" + settings.dbLockName() + "'";
                    logger.warning(message);
                    return;
                }

                long runId = requireActiveRunId(connection);
                boolean hasNameSource = tableHasColumn(connection, "player_profile", "name_source");
                boolean hasNameCheckedAt = tableHasColumn(connection, "player_profile", "name_checked_at");
                int effectiveMax = maxPerRunOverride == null
                        ? settings.nameResolverMaxPerRun()
                        : Math.max(1, maxPerRunOverride);
                logger.info("Name resolver started: reason=" + reason + ", max=" + effectiveMax);
                NameResolverResult result = resolveNamesFromMojang(
                        connection,
                        runId,
                        hasNameSource,
                        hasNameCheckedAt,
                        effectiveMax
                );
                message = "Names resolved: candidates=" + result.candidates()
                        + ", resolved=" + result.resolved()
                        + ", failed=" + result.failed()
                        + ", skipped=" + result.skipped()
                        + ", max=" + effectiveMax;
                success = true;
            } finally {
                if (lockAcquired) {
                    try {
                        releaseDbLock(connection, settings.dbLockName());
                    } catch (SQLException ex) {
                        logger.log(Level.WARNING, "Could not release DB lock.", ex);
                    }
                }
            }
        } catch (Exception ex) {
            message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (shuttingDown.get()) {
                logger.log(Level.INFO, "Name resolver stopped due to plugin shutdown: " + message);
            } else {
                logger.log(Level.SEVERE, "Name resolver run failed", ex);
            }
        } finally {
            ImportSummary summary = new ImportSummary(
                    startedAt,
                    Instant.now(),
                    success,
                    reason,
                    message,
                    0,
                    0,
                    0
            );
            lastSummary = summary;
            running.set(false);
            logger.info("Name resolver finished. success=" + success
                    + ", reason=" + reason
                    + ", duration=" + summary.durationSeconds() + "s");
        }
    }

    private int drainReadyResults(
            Connection connection,
            ExecutorCompletionService<ComputationResult> completionService,
            List<UUID> changedUuidsBuffer,
            List<StatsRow> statsRowsBuffer,
            List<MetricValueRow> metricRowsBuffer,
            long runId,
            boolean forceBlocking
    ) throws Exception {
        int drained = 0;
        if (forceBlocking) {
            // During shutdown phase we block for at least one result to guarantee forward progress.
            Future<ComputationResult> future = completionService.take();
            appendComputedResult(future.get(), changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer);
            drained++;
        }

        Future<ComputationResult> ready;
        while ((ready = completionService.poll()) != null) {
            appendComputedResult(ready.get(), changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer);
            drained++;
        }

        if (changedUuidsBuffer.size() >= settings.flushChanged()) {
            flushChangedBatch(connection, runId, changedUuidsBuffer, statsRowsBuffer, metricRowsBuffer);
        }
        return drained;
    }

    private void appendComputedResult(
            ComputationResult result,
            List<UUID> changedUuidsBuffer,
            List<StatsRow> statsRowsBuffer,
            List<MetricValueRow> metricRowsBuffer
    ) {
        changedUuidsBuffer.add(result.uuid());
        statsRowsBuffer.add(new StatsRow(result.uuid(), result.statsGzip(), result.statsSha1()));
        metricRowsBuffer.addAll(result.metricRows());
    }

    private Map<UUID, String> loadUsercache(Path usercachePath) {
        if (!Files.isRegularFile(usercachePath)) {
            return Collections.emptyMap();
        }
        Map<UUID, String> names = new HashMap<>();
        try {
            List<Map<String, Object>> values = objectMapper.readValue(
                    usercachePath.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            for (Map<String, Object> entry : values) {
                Object rawName = entry.get("name");
                Object rawUuid = entry.get("uuid");
                if (!(rawName instanceof String name) || name.isBlank()) {
                    continue;
                }
                if (!(rawUuid instanceof String uuidText) || uuidText.isBlank()) {
                    continue;
                }
                try {
                    UUID uuid = UuidCodec.parseFlexible(uuidText);
                    names.put(uuid, name.substring(0, Math.min(16, name.length())));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid UUID values in usercache.
                }
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Could not load usercache from " + usercachePath, ex);
        }
        return names;
    }

    private BanFileLoadResult loadBannedPlayers(Path bannedPlayersPath) {
        if (!Files.isRegularFile(bannedPlayersPath)) {
            return new BanFileLoadResult(false, Collections.emptyList(), "file not found");
        }

        List<BanEntry> bans = new ArrayList<>();
        try {
            List<Map<String, Object>> values = objectMapper.readValue(
                    bannedPlayersPath.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );

            for (Map<String, Object> entry : values) {
                Object rawUuid = entry.get("uuid");
                if (!(rawUuid instanceof String uuidText) || uuidText.isBlank()) {
                    continue;
                }

                UUID uuid;
                try {
                    uuid = UuidCodec.parseFlexible(uuidText);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                String name = normalizePlayerName(entry.get("name"));
                if (name == null) {
                    name = fallbackNameFromUuid(uuid);
                }
                String reason = normalizeText(entry.get("reason"), 512);
                String sourceRaw = normalizeText(entry.get("source"), 512);
                String bannedBy = normalizeText(stripMinecraftColorCodes(sourceRaw), 64);
                String expiresRaw = normalizeText(entry.get("expires"), 64);
                Timestamp bannedAt = parseMinecraftBanTimestamp(entry.get("created"));
                boolean permanent = isPermanentBan(expiresRaw);
                Timestamp expiresAt = permanent ? null : parseMinecraftBanTimestamp(expiresRaw);

                bans.add(new BanEntry(
                        uuid,
                        name,
                        name.toLowerCase(Locale.ROOT),
                        reason,
                        bannedBy,
                        bannedAt,
                        expiresAt,
                        permanent,
                        sourceRaw,
                        expiresRaw
                ));
            }

            return new BanFileLoadResult(true, bans, "loaded");
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Could not load banned-players.json from " + bannedPlayersPath, ex);
            return new BanFileLoadResult(false, Collections.emptyList(), "parse failed");
        }
    }

    private KnownSyncResult syncKnownPlayers(
            Connection connection,
            Set<UUID> knownStatsSourceUuids,
            Set<UUID> statsIncludedUuids,
            Map<UUID, String> usercacheNames,
            BanFileLoadResult banFile
    ) throws SQLException {
        if (!tableExists(connection, "player_known")) {
            return new KnownSyncResult("table missing");
        }

        List<KnownPlayerRow> rows = buildKnownPlayerRows(
                knownStatsSourceUuids,
                statsIncludedUuids,
                usercacheNames,
                banFile.entries()
        );
        if (!rows.isEmpty()) {
            String sql = """
                    INSERT INTO player_known (
                      uuid, name, name_lc, name_source, name_priority, first_seen, last_seen, seen_in_stats, seen_in_usercache, seen_in_bans
                    )
                    VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      last_seen = VALUES(last_seen),
                      seen_in_stats = IF(seen_in_stats = 1 OR VALUES(seen_in_stats) = 1, 1, 0),
                      seen_in_usercache = IF(seen_in_usercache = 1 OR VALUES(seen_in_usercache) = 1, 1, 0),
                      seen_in_bans = IF(seen_in_bans = 1 OR VALUES(seen_in_bans) = 1, 1, 0),
                      name = CASE
                        WHEN VALUES(name) IS NOT NULL AND VALUES(name_priority) >= name_priority THEN VALUES(name)
                        ELSE name
                      END,
                      name_lc = CASE
                        WHEN VALUES(name_lc) IS NOT NULL AND VALUES(name_priority) >= name_priority THEN VALUES(name_lc)
                        ELSE name_lc
                      END,
                      name_source = CASE
                        WHEN VALUES(name) IS NOT NULL AND VALUES(name_priority) >= name_priority THEN VALUES(name_source)
                        ELSE name_source
                      END,
                      name_priority = GREATEST(name_priority, VALUES(name_priority))
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (KnownPlayerRow row : rows) {
                    stmt.setBytes(1, UuidCodec.toBytes(row.uuid()));
                    stmt.setString(2, row.name());
                    stmt.setString(3, row.nameLc());
                    stmt.setString(4, row.nameSource());
                    stmt.setInt(5, row.namePriority());
                    stmt.setInt(6, row.seenInStats() ? 1 : 0);
                    stmt.setInt(7, row.seenInUsercache() ? 1 : 0);
                    stmt.setInt(8, row.seenInBans() ? 1 : 0);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }

        int backfilled = ensureKnownNamesPresent(connection);
        connection.commit();
        return new KnownSyncResult("upserted=" + rows.size() + ", backfilled=" + backfilled);
    }

    private List<KnownPlayerRow> buildKnownPlayerRows(
            Set<UUID> knownStatsSourceUuids,
            Set<UUID> statsIncludedUuids,
            Map<UUID, String> usercacheNames,
            List<BanEntry> bans
    ) {
        Map<UUID, KnownPlayerAccumulator> byUuid = new HashMap<>();

        for (UUID uuid : knownStatsSourceUuids) {
            byUuid.computeIfAbsent(uuid, ignored -> new KnownPlayerAccumulator(uuid));
        }

        for (UUID uuid : statsIncludedUuids) {
            KnownPlayerAccumulator acc = byUuid.computeIfAbsent(uuid, ignored -> new KnownPlayerAccumulator(uuid));
            acc.markSeenInStats();
        }

        for (Map.Entry<UUID, String> entry : usercacheNames.entrySet()) {
            KnownPlayerAccumulator acc = byUuid.computeIfAbsent(entry.getKey(), ignored -> new KnownPlayerAccumulator(entry.getKey()));
            acc.markSeenInUsercache();
            String name = normalizePlayerName(entry.getValue());
            acc.considerName(name, "usercache", KNOWN_NAME_PRIORITY_USERCACHE);
        }

        for (BanEntry ban : bans) {
            KnownPlayerAccumulator acc = byUuid.computeIfAbsent(ban.uuid(), ignored -> new KnownPlayerAccumulator(ban.uuid()));
            acc.markSeenInBans();
            acc.considerName(ban.name(), "banlist", KNOWN_NAME_PRIORITY_BANLIST);
        }

        List<KnownPlayerRow> rows = new ArrayList<>(byUuid.size());
        for (KnownPlayerAccumulator acc : byUuid.values()) {
            acc.ensureNamePresent();
            rows.add(new KnownPlayerRow(
                    acc.uuid(),
                    acc.name(),
                    acc.nameLc(),
                    acc.nameSource(),
                    acc.namePriority(),
                    acc.seenInStats(),
                    acc.seenInUsercache(),
                    acc.seenInBans()
            ));
        }
        return rows;
    }

    private int ensureKnownNamesPresent(Connection connection) throws SQLException {
        String sql = """
                UPDATE player_known
                SET
                  name = COALESCE(NULLIF(name, ''), LOWER(SUBSTR(HEX(uuid), 1, 12))),
                  name_lc = LOWER(COALESCE(NULLIF(name, ''), SUBSTR(HEX(uuid), 1, 12))),
                  name_source = CASE
                    WHEN (name IS NULL OR name='') AND (name_source IS NULL OR name_source='' OR name_source='unknown')
                      THEN 'fallback'
                    ELSE COALESCE(NULLIF(name_source, ''), 'unknown')
                  END,
                  name_priority = CASE
                    WHEN (name IS NULL OR name='') AND name_priority < ? THEN ?
                    ELSE name_priority
                  END
                WHERE name IS NULL
                   OR name=''
                   OR name_lc IS NULL
                   OR name_lc=''
                   OR name_source IS NULL
                   OR name_source=''
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, KNOWN_NAME_PRIORITY_FALLBACK);
            stmt.setInt(2, KNOWN_NAME_PRIORITY_FALLBACK);
            return stmt.executeUpdate();
        }
    }

    private void addKnownNameUpdateBatch(
            PreparedStatement stmt,
            UUID uuid,
            String resolvedName,
            boolean seenInStats
    ) throws SQLException {
        stmt.setBytes(1, UuidCodec.toBytes(uuid));
        stmt.setString(2, resolvedName);
        stmt.setString(3, resolvedName.toLowerCase(Locale.ROOT));
        stmt.setInt(4, KNOWN_NAME_PRIORITY_MOJANG);
        stmt.setInt(5, seenInStats ? 1 : 0);
        stmt.addBatch();
    }

    private void flushNameResolverBatchesIfNeeded(
            Connection connection,
            PreparedStatement updateResolved,
            PreparedStatement updateChecked,
            PreparedStatement updateKnown,
            PreparedStatement updateKnownChecked,
            NameResolverBatchState batchState,
            boolean force
    ) throws SQLException {
        boolean intervalElapsed = Duration.between(
                batchState.lastFlushAt(),
                Instant.now()
        ).compareTo(NAME_RESOLVER_BATCH_FLUSH_INTERVAL) >= 0;
        if (!force
                && batchState.pendingRows() < NAME_RESOLVER_BATCH_FLUSH_ROWS
                && !intervalElapsed) {
            return;
        }

        if (!batchState.hasPendingRows()) {
            if (intervalElapsed) {
                keepNameResolverConnectionAlive(connection);
                batchState.markFlushed();
            }
            return;
        }

        if (batchState.pendingUpdatedProfileRows > 0) {
            updateResolved.executeBatch();
            updateResolved.clearBatch();
        }
        if (batchState.pendingUpdatedKnownRows > 0 && updateKnown != null) {
            updateKnown.executeBatch();
            updateKnown.clearBatch();
        }
        if (batchState.pendingCheckedProfileRows > 0 && updateChecked != null) {
            updateChecked.executeBatch();
            updateChecked.clearBatch();
        }
        if (batchState.pendingCheckedKnownRows > 0 && updateKnownChecked != null) {
            updateKnownChecked.executeBatch();
            updateKnownChecked.clearBatch();
        }
        connection.commit();
        batchState.markFlushed();
    }

    private void keepNameResolverConnectionAlive(Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1")) {
            try (ResultSet ignored = stmt.executeQuery()) {
                // Keep the DB connection from going idle while Mojang requests are slow.
            }
        }
        connection.commit();
    }

    private BanSyncResult syncBans(Connection connection, long runId, BanFileLoadResult banFile) throws SQLException {
        if (!tableExists(connection, "player_ban")) {
            return new BanSyncResult("table missing");
        }
        if (!banFile.syncable()) {
            int backfilled = ensureBanNamesPresent(connection);
            if (backfilled > 0) {
                connection.commit();
            }
            return new BanSyncResult("skipped (" + banFile.note() + "), backfilled=" + backfilled);
        }

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM player_ban WHERE run_id=?")) {
            delete.setLong(1, runId);
            delete.executeUpdate();
        }

        if (!banFile.entries().isEmpty()) {
            String sql = """
                    INSERT INTO player_ban (
                      run_id, uuid, name, name_lc, reason, banned_by, banned_at, expires_at, is_permanent, source_raw, expires_raw
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (BanEntry ban : banFile.entries()) {
                    insert.setLong(1, runId);
                    insert.setBytes(2, UuidCodec.toBytes(ban.uuid()));
                    insert.setString(3, ban.name());
                    insert.setString(4, ban.nameLc());
                    insert.setString(5, ban.reason());
                    insert.setString(6, ban.bannedBy());
                    insert.setTimestamp(7, ban.bannedAt());
                    insert.setTimestamp(8, ban.expiresAt());
                    insert.setInt(9, ban.permanent() ? 1 : 0);
                    insert.setString(10, ban.sourceRaw());
                    insert.setString(11, ban.expiresRaw());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        int backfilled = ensureBanNamesPresent(connection);
        connection.commit();
        return new BanSyncResult("synced=" + banFile.entries().size() + ", backfilled=" + backfilled);
    }

    private int ensureBanNamesPresent(Connection connection) throws SQLException {
        String sql = """
                UPDATE player_ban
                SET
                  name = COALESCE(NULLIF(name, ''), LOWER(SUBSTR(HEX(uuid), 1, 12))),
                  name_lc = LOWER(COALESCE(NULLIF(name, ''), SUBSTR(HEX(uuid), 1, 12)))
                WHERE name IS NULL
                   OR name=''
                   OR name_lc IS NULL
                   OR name_lc=''
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            return stmt.executeUpdate();
        }
    }

    private String normalizePlayerName(Object rawValue) {
        String value = normalizeText(rawValue, 16);
        return value == null || value.isBlank() ? null : value;
    }

    private String normalizeText(Object rawValue, int maxLen) {
        if (!(rawValue instanceof String text)) {
            return null;
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen);
    }

    private String stripMinecraftColorCodes(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("(?i)\\u00A7[0-9A-FK-OR]", "").trim();
    }

    private boolean isPermanentBan(String expiresRaw) {
        if (expiresRaw == null || expiresRaw.isBlank()) {
            return true;
        }
        String normalized = expiresRaw.trim().toLowerCase(Locale.ROOT);
        return "forever".equals(normalized)
                || "permanent".equals(normalized)
                || "never".equals(normalized)
                || "none".equals(normalized);
    }

    private Timestamp parseMinecraftBanTimestamp(Object rawValue) {
        if (!(rawValue instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text.trim(), MINECRAFT_BAN_DATE_FORMAT);
            return Timestamp.from(parsed.toInstant());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Long findActiveRunId(Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT active_run_id FROM site_state WHERE id=1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                long value = rs.getLong(1);
                if (!rs.wasNull()) {
                    return value;
                }
            }
        }
        return null;
    }

    private long createLoadingRun(Connection connection) throws SQLException {
        long newRunId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO import_run (generated_at, status) VALUES (NOW(), 'loading')",
                PreparedStatement.RETURN_GENERATED_KEYS
        )) {
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Could not read generated run_id");
                }
                newRunId = keys.getLong(1);
            }
        }
        connection.commit();
        return newRunId;
    }

    private void publishRun(Connection connection, long runId) throws SQLException {
        try (PreparedStatement touch = connection.prepareStatement(
                "UPDATE import_run SET generated_at=NOW(), status='active' WHERE id=?"
        );
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE site_state SET active_run_id=? WHERE id=1"
             )
        ) {
            touch.setLong(1, runId);
            touch.executeUpdate();
            update.setLong(1, runId);
            update.executeUpdate();
        }
        connection.commit();
    }

    private void validateInputSafety(int processed, int kept) {
        int minProcessed = settings.safetyMinProcessedFiles();
        if (minProcessed > 0 && processed < minProcessed) {
            throw new IllegalStateException("Stats input safety failed: processed files " + processed
                    + " < configured minimum " + minProcessed);
        }

        int minKept = settings.safetyMinKeptPlayers();
        if (minKept > 0 && kept < minKept) {
            throw new IllegalStateException("Stats input safety failed: kept players " + kept
                    + " < configured minimum " + minKept);
        }
    }

    private void validateParseErrorSafety(int parseErrors) {
        int maxParseErrors = settings.safetyMaxParseErrors();
        if (maxParseErrors >= 0 && parseErrors > maxParseErrors) {
            throw new IllegalStateException("Stats input safety failed: parse errors " + parseErrors
                    + " > configured maximum " + maxParseErrors);
        }
    }

    private void markRunFailed(long runId, String message) {
        String note = message == null ? "Import failed" : message;
        if (note.length() > 255) {
            note = note.substring(0, 255);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "UPDATE import_run SET status='failed', note=? WHERE id=? AND status='loading'"
             )) {
            stmt.setString(1, note);
            stmt.setLong(2, runId);
            stmt.executeUpdate();
            connection.commit();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Could not mark failed import_run id=" + runId, ex);
        }
    }

    private int cleanupOldRuns(Connection connection, long activeRunId) throws SQLException {
        int keepRuns = settings.retentionKeepRuns();
        if (keepRuns <= 0) {
            return 0;
        }

        List<Long> keepIds = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT id FROM import_run ORDER BY id DESC LIMIT ?"
        )) {
            stmt.setInt(1, keepRuns);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    keepIds.add(rs.getLong(1));
                }
            }
        }
        if (!keepIds.contains(activeRunId)) {
            keepIds.add(activeRunId);
        }
        if (keepIds.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", Collections.nCopies(keepIds.size(), "?"));
        String sql = "DELETE FROM import_run WHERE id NOT IN (" + placeholders + ")";
        int deleted;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < keepIds.size(); i++) {
                stmt.setLong(i + 1, keepIds.get(i));
            }
            deleted = stmt.executeUpdate();
        }
        connection.commit();
        return deleted;
    }

    private long requireActiveRunId(Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT active_run_id FROM site_state WHERE id=1");
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("site_state row id=1 missing");
            }
            long value = rs.getLong(1);
            if (rs.wasNull()) {
                throw new IllegalStateException("No active run_id available. Run a normal import first.");
            }
            return value;
        }
    }

    private Map<String, List<MetricSource>> loadMetricSources(Connection connection) throws SQLException {
        String sql = """
                SELECT ms.metric_id, ms.section, ms.mc_key, ms.weight
                FROM metric_source ms
                JOIN metric_def md ON md.id = ms.metric_id
                WHERE md.enabled = 1
                ORDER BY md.sort_order ASC, ms.metric_id ASC
                """;
        Map<String, List<MetricSource>> byMetric = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String metricId = rs.getString(1);
                MetricSource source = new MetricSource(
                        metricId,
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4)
                );
                byMetric.computeIfAbsent(metricId, ignored -> new ArrayList<>()).add(source);
            }
        }
        return byMetric;
    }

    private Map<UUID, byte[]> loadExistingHashes(Connection connection, long runId) throws SQLException {
        Map<UUID, byte[]> map = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT uuid, stats_sha1 FROM player_stats WHERE run_id=?"
        )) {
            stmt.setLong(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byte[] uuidBytes = rs.getBytes(1);
                    byte[] sha = rs.getBytes(2);
                    if (uuidBytes == null || uuidBytes.length != 16 || sha == null || sha.length != 20) {
                        continue;
                    }
                    map.put(UuidCodec.fromBytes(uuidBytes), sha);
                }
            }
        }
        return map;
    }

    private Map<UUID, PlayerProfileMeta> loadExistingProfiles(
            Connection connection,
            long runId,
            boolean hasNameSource,
            boolean hasNameCheckedAt
    ) throws SQLException {
        List<String> columns = new ArrayList<>();
        columns.add("uuid");
        columns.add("name");
        if (hasNameSource) {
            columns.add("name_source");
        }
        if (hasNameCheckedAt) {
            columns.add("name_checked_at");
        }

        String sql = "SELECT " + String.join(", ", columns) + " FROM player_profile WHERE run_id=?";
        Map<UUID, PlayerProfileMeta> map = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byte[] uuidBytes = rs.getBytes(1);
                    String name = rs.getString(2);
                    if (uuidBytes == null || uuidBytes.length != 16 || name == null || name.isBlank()) {
                        continue;
                    }
                    String source = hasNameSource ? rs.getString(3) : null;
                    Timestamp checkedAt = hasNameCheckedAt ? rs.getTimestamp(columns.size()) : null;
                    map.put(
                            UuidCodec.fromBytes(uuidBytes),
                            new PlayerProfileMeta(name, source, checkedAt)
                    );
                }
            }
        }
        return map;
    }

    private boolean tableHasColumn(Connection connection, String table, String column) throws SQLException {
        String sql = """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                LIMIT 1
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                LIMIT 1
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean acquireDbLock(Connection connection, String lockName, int timeoutSeconds) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            stmt.setString(1, lockName);
            stmt.setInt(2, timeoutSeconds);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt(1) == 1;
            }
        }
    }

    private void releaseDbLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("DO RELEASE_LOCK(?)")) {
            stmt.setString(1, lockName);
            stmt.execute();
        }
        connection.commit();
    }

    private void flushProfiles(
            Connection connection,
            List<ProfileRow> profileRows,
            boolean hasNameSource,
            boolean hasNameCheckedAt
    ) throws SQLException {
        if (profileRows.isEmpty()) {
            return;
        }

        final String sql;
        if (hasNameSource && hasNameCheckedAt) {
            sql = """
                    INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, name_checked_at, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      name = VALUES(name),
                      name_lc = VALUES(name_lc),
                      name_source = VALUES(name_source),
                      name_checked_at = VALUES(name_checked_at),
                      last_seen = VALUES(last_seen)
                    """;
        } else if (hasNameSource) {
            sql = """
                    INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      name = VALUES(name),
                      name_lc = VALUES(name_lc),
                      name_source = VALUES(name_source),
                      last_seen = VALUES(last_seen)
                    """;
        } else {
            sql = """
                    INSERT INTO player_profile (run_id, uuid, name, name_lc, last_seen)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      name = VALUES(name),
                      name_lc = VALUES(name_lc),
                      last_seen = VALUES(last_seen)
                    """;
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (ProfileRow row : profileRows) {
                int idx = 1;
                stmt.setLong(idx++, row.runId());
                stmt.setBytes(idx++, UuidCodec.toBytes(row.uuid()));
                stmt.setString(idx++, row.name());
                stmt.setString(idx++, row.nameLc());
                if (hasNameSource && hasNameCheckedAt) {
                    stmt.setString(idx++, row.nameSource());
                    stmt.setTimestamp(idx++, row.nameCheckedAt());
                    stmt.setTimestamp(idx, row.lastSeen());
                } else if (hasNameSource) {
                    stmt.setString(idx++, row.nameSource());
                    stmt.setTimestamp(idx, row.lastSeen());
                } else {
                    stmt.setTimestamp(idx, row.lastSeen());
                }
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        connection.commit();
        profileRows.clear();
    }

    private void copyPreviousSnapshotBatch(
            Connection connection,
            long fromRunId,
            long toRunId,
            List<UUID> uuids,
            boolean hasNameSource,
            boolean hasNameCheckedAt
    ) throws SQLException {
        if (uuids.isEmpty()) {
            return;
        }
        copyPreviousProfilesBatch(connection, fromRunId, toRunId, uuids, hasNameSource, hasNameCheckedAt);
        copyPreviousStatsAndMetricsBatch(connection, fromRunId, toRunId, uuids);
    }

    private void copyPreviousProfilesBatch(
            Connection connection,
            long fromRunId,
            long toRunId,
            List<UUID> uuids,
            boolean hasNameSource,
            boolean hasNameCheckedAt
    ) throws SQLException {
        for (int i = 0; i < uuids.size(); i += 1500) {
            List<UUID> chunk = uuids.subList(i, Math.min(uuids.size(), i + 1500));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            final String sql;
            if (hasNameSource && hasNameCheckedAt) {
                sql = """
                        INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, name_checked_at, last_seen)
                        SELECT ?, uuid, name, name_lc, name_source, name_checked_at, last_seen
                        FROM player_profile
                        WHERE run_id=? AND uuid IN (%s)
                        ON DUPLICATE KEY UPDATE
                          name = VALUES(name),
                          name_lc = VALUES(name_lc),
                          name_source = VALUES(name_source),
                          name_checked_at = VALUES(name_checked_at),
                          last_seen = VALUES(last_seen)
                        """.formatted(placeholders);
            } else if (hasNameSource) {
                sql = """
                        INSERT INTO player_profile (run_id, uuid, name, name_lc, name_source, last_seen)
                        SELECT ?, uuid, name, name_lc, name_source, last_seen
                        FROM player_profile
                        WHERE run_id=? AND uuid IN (%s)
                        ON DUPLICATE KEY UPDATE
                          name = VALUES(name),
                          name_lc = VALUES(name_lc),
                          name_source = VALUES(name_source),
                          last_seen = VALUES(last_seen)
                        """.formatted(placeholders);
            } else {
                sql = """
                        INSERT INTO player_profile (run_id, uuid, name, name_lc, last_seen)
                        SELECT ?, uuid, name, name_lc, last_seen
                        FROM player_profile
                        WHERE run_id=? AND uuid IN (%s)
                        ON DUPLICATE KEY UPDATE
                          name = VALUES(name),
                          name_lc = VALUES(name_lc),
                          last_seen = VALUES(last_seen)
                        """.formatted(placeholders);
            }
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                bindRunCopyChunk(stmt, toRunId, fromRunId, chunk);
                stmt.executeUpdate();
            }
        }
        connection.commit();
    }

    private void copyPreviousStatsAndMetricsBatch(
            Connection connection,
            long fromRunId,
            long toRunId,
            List<UUID> uuids
    ) throws SQLException {
        if (uuids.isEmpty()) {
            return;
        }
        for (int i = 0; i < uuids.size(); i += 1500) {
            List<UUID> chunk = uuids.subList(i, Math.min(uuids.size(), i + 1500));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));

            String statsSql = """
                    INSERT INTO player_stats (run_id, uuid, stats_gzip, stats_sha1, updated_at)
                    SELECT ?, uuid, stats_gzip, stats_sha1, updated_at
                    FROM player_stats
                    WHERE run_id=? AND uuid IN (%s)
                    ON DUPLICATE KEY UPDATE
                      stats_gzip = VALUES(stats_gzip),
                      stats_sha1 = VALUES(stats_sha1),
                      updated_at = VALUES(updated_at)
                    """.formatted(placeholders);
            try (PreparedStatement stmt = connection.prepareStatement(statsSql)) {
                bindRunCopyChunk(stmt, toRunId, fromRunId, chunk);
                stmt.executeUpdate();
            }

            String metricsSql = """
                    INSERT INTO metric_value (run_id, metric_id, uuid, value)
                    SELECT ?, metric_id, uuid, value
                    FROM metric_value
                    WHERE run_id=? AND uuid IN (%s)
                    ON DUPLICATE KEY UPDATE value = VALUES(value)
                    """.formatted(placeholders);
            try (PreparedStatement stmt = connection.prepareStatement(metricsSql)) {
                bindRunCopyChunk(stmt, toRunId, fromRunId, chunk);
                stmt.executeUpdate();
            }
        }
        connection.commit();
        uuids.clear();
    }

    private void bindRunCopyChunk(PreparedStatement stmt, long toRunId, long fromRunId, List<UUID> chunk) throws SQLException {
        stmt.setLong(1, toRunId);
        stmt.setLong(2, fromRunId);
        int index = 3;
        for (UUID uuid : chunk) {
            stmt.setBytes(index++, UuidCodec.toBytes(uuid));
        }
    }

    private void flushChangedBatch(
            Connection connection,
            long runId,
            List<UUID> changedUuids,
            List<StatsRow> statsRows,
            List<MetricValueRow> metricRows
    ) throws SQLException {
        if (changedUuids.isEmpty()) {
            return;
        }

        // Split DELETE ... IN (...) to avoid very large statements and placeholder limits.
        for (int i = 0; i < changedUuids.size(); i += 1500) {
            List<UUID> chunk = changedUuids.subList(i, Math.min(changedUuids.size(), i + 1500));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql = "DELETE FROM metric_value WHERE run_id=? AND uuid IN (" + placeholders + ")";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setLong(1, runId);
                int index = 2;
                for (UUID uuid : chunk) {
                    stmt.setBytes(index++, UuidCodec.toBytes(uuid));
                }
                stmt.executeUpdate();
            }
        }
        connection.commit();

        if (!statsRows.isEmpty()) {
            String sql = """
                    INSERT INTO player_stats (run_id, uuid, stats_gzip, stats_sha1, updated_at)
                    VALUES (?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                      stats_gzip = VALUES(stats_gzip),
                      stats_sha1 = VALUES(stats_sha1),
                      updated_at = VALUES(updated_at)
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (StatsRow row : statsRows) {
                    stmt.setLong(1, runId);
                    stmt.setBytes(2, UuidCodec.toBytes(row.uuid()));
                    stmt.setBytes(3, row.statsGzip());
                    stmt.setBytes(4, row.statsSha1());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        }

        if (!metricRows.isEmpty()) {
            String sql = """
                    INSERT INTO metric_value (run_id, metric_id, uuid, value)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE value = VALUES(value)
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (MetricValueRow row : metricRows) {
                    stmt.setLong(1, runId);
                    stmt.setString(2, row.metricId());
                    stmt.setBytes(3, UuidCodec.toBytes(row.uuid()));
                    stmt.setLong(4, row.value());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        }

        changedUuids.clear();
        statsRows.clear();
        metricRows.clear();
    }

    private void recomputeKingPoints(Connection connection, long runId, List<String> metricIds) throws SQLException {
        String kingMetricId = settings.kingMetricId();
        List<Integer> points = settings.kingPoints();
        int p1 = points.get(0);
        int p2 = points.get(1);
        int p3 = points.get(2);

        // Rebuild king values from scratch each run to avoid carrying stale ranking points forward.
        ensureKingMetric(connection, kingMetricId);
        boolean hasAwardsTable = tableExists(connection, "metric_award");
        Map<UUID, Integer> kingScoreByPlayer = new HashMap<>();
        List<AwardRow> awardRows = new ArrayList<>();

        try (PreparedStatement deleteKing = connection.prepareStatement(
                "DELETE FROM metric_value WHERE run_id=? AND metric_id=?"
        )) {
            deleteKing.setLong(1, runId);
            deleteKing.setString(2, kingMetricId);
            deleteKing.executeUpdate();
        }
        if (hasAwardsTable) {
            try (PreparedStatement deleteAwards = connection.prepareStatement(
                    "DELETE FROM metric_award WHERE run_id=?"
            )) {
                deleteAwards.setLong(1, runId);
                deleteAwards.executeUpdate();
            }
        }
        connection.commit();

        String top3Sql = """
                SELECT uuid, value
                FROM metric_value
                WHERE run_id=? AND metric_id=? AND value > 0
                ORDER BY value DESC, uuid ASC
                LIMIT 3
                """;
        try (PreparedStatement top3 = connection.prepareStatement(top3Sql)) {
            for (String metricId : metricIds) {
                if (metricId.equals(kingMetricId)) {
                    continue;
                }
                top3.setLong(1, runId);
                top3.setString(2, metricId);
                try (ResultSet rs = top3.executeQuery()) {
                    int place = 0;
                    while (rs.next() && place < 3) {
                        place++;
                        int pointsForPlace = switch (place) {
                            case 1 -> p1;
                            case 2 -> p2;
                            default -> p3;
                        };
                        if (pointsForPlace <= 0) {
                            continue;
                        }
                        byte[] uuidBytes = rs.getBytes(1);
                        if (uuidBytes == null || uuidBytes.length != 16) {
                            continue;
                        }
                        UUID uuid = UuidCodec.fromBytes(uuidBytes);
                        long value = rs.getLong(2);
                        kingScoreByPlayer.merge(uuid, pointsForPlace, Integer::sum);
                        if (hasAwardsTable) {
                            awardRows.add(new AwardRow(runId, metricId, place, uuid, value, pointsForPlace));
                        }
                    }
                }
            }
        }

        if (hasAwardsTable && !awardRows.isEmpty()) {
            String awardSql = """
                    INSERT INTO metric_award (run_id, metric_id, place, uuid, points, value)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(awardSql)) {
                for (AwardRow row : awardRows) {
                    stmt.setLong(1, row.runId());
                    stmt.setString(2, row.metricId());
                    stmt.setInt(3, row.place());
                    stmt.setBytes(4, UuidCodec.toBytes(row.uuid()));
                    stmt.setInt(5, row.points());
                    stmt.setLong(6, row.value());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        }

        if (!kingScoreByPlayer.isEmpty()) {
            String kingSql = """
                    INSERT INTO metric_value (run_id, metric_id, uuid, value)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE value = VALUES(value)
                    """;
            try (PreparedStatement stmt = connection.prepareStatement(kingSql)) {
                for (Map.Entry<UUID, Integer> entry : kingScoreByPlayer.entrySet()) {
                    stmt.setLong(1, runId);
                    stmt.setString(2, kingMetricId);
                    stmt.setBytes(3, UuidCodec.toBytes(entry.getKey()));
                    stmt.setLong(4, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        }
    }

    private void ensureKingMetric(Connection connection, String kingMetricId) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM metric_def WHERE id=? LIMIT 1")) {
            exists.setString(1, kingMetricId);
            try (ResultSet rs = exists.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        String sql = """
                INSERT INTO metric_def (id, label, category, unit, divisor, decimals, sort_order, enabled)
                VALUES (?, 'Server-König', 'Allgemein', 'Punkte', 1, 0, 0, 1)
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, kingMetricId);
            insert.executeUpdate();
        }
        connection.commit();
    }

    private NameResolverResult resolveNamesFromMojang(
            Connection connection,
            long runId,
            boolean hasNameSource,
            boolean hasNameCheckedAt,
            int maxCandidates
    ) throws Exception {
        List<NameCandidate> profileCandidates = loadNameResolverCandidates(
                connection,
                runId,
                hasNameSource,
                hasNameCheckedAt,
                maxCandidates
        );
        boolean hasPlayerKnown = tableExists(connection, "player_known");
        boolean hasKnownNameCheckedAt = hasPlayerKnown && ensureKnownNameCheckedAtColumn(connection);
        Set<UUID> profileCandidateUuids = new HashSet<>();
        for (NameCandidate candidate : profileCandidates) {
            profileCandidateUuids.add(candidate.uuid());
        }
        int remainingBudget = Math.max(0, maxCandidates - profileCandidates.size());
        List<NameCandidate> knownCandidates = hasPlayerKnown && remainingBudget > 0
                ? loadKnownNameResolverCandidates(connection, profileCandidateUuids, remainingBudget, hasKnownNameCheckedAt)
                : Collections.emptyList();
        int totalCandidates = profileCandidates.size() + knownCandidates.size();
        if (totalCandidates == 0) {
            int backfilled = hasPlayerKnown ? ensureKnownNamesPresent(connection) : 0;
            if (backfilled > 0) {
                connection.commit();
            }
            logger.info("Name resolver: no candidates.");
            return new NameResolverResult(0, 0, 0, 0);
        }

        final String updateSuccessSql;
        if (hasNameSource && hasNameCheckedAt) {
            updateSuccessSql = """
                    UPDATE player_profile
                    SET name=?, name_lc=?, name_source='mojang', name_checked_at=NOW()
                    WHERE run_id=? AND uuid=?
                    """;
        } else if (hasNameSource) {
            updateSuccessSql = """
                    UPDATE player_profile
                    SET name=?, name_lc=?, name_source='mojang'
                    WHERE run_id=? AND uuid=?
                    """;
        } else {
            updateSuccessSql = """
                    UPDATE player_profile
                    SET name=?, name_lc=?
                    WHERE run_id=? AND uuid=?
                    """;
        }

        int resolved = 0;
        int failed = 0;
        int skipped = 0;
        int processed = 0;
        int sleepMs = settings.nameResolverSleepMs();
        NameResolverBatchState batchState = new NameResolverBatchState();

        final String updateKnownSql;
        if (hasPlayerKnown && hasKnownNameCheckedAt) {
            updateKnownSql = """
                    INSERT INTO player_known (
                      uuid, name, name_lc, name_source, name_priority, first_seen, last_seen, name_checked_at, seen_in_stats, seen_in_usercache, seen_in_bans
                    )
                    VALUES (?, ?, ?, 'mojang', ?, NOW(), NOW(), NOW(), ?, 0, 0)
                    ON DUPLICATE KEY UPDATE
                      last_seen = NOW(),
                      name_checked_at = NOW(),
                      seen_in_stats = IF(seen_in_stats = 1 OR VALUES(seen_in_stats) = 1, 1, 0),
                      name = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name)
                        ELSE name
                      END,
                      name_lc = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name_lc)
                        ELSE name_lc
                      END,
                      name_source = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name_source)
                        ELSE name_source
                      END,
                      name_priority = GREATEST(name_priority, VALUES(name_priority))
                    """;
        } else if (hasPlayerKnown) {
            updateKnownSql = """
                    INSERT INTO player_known (
                      uuid, name, name_lc, name_source, name_priority, first_seen, last_seen, seen_in_stats, seen_in_usercache, seen_in_bans
                    )
                    VALUES (?, ?, ?, 'mojang', ?, NOW(), NOW(), ?, 0, 0)
                    ON DUPLICATE KEY UPDATE
                      last_seen = NOW(),
                      seen_in_stats = IF(seen_in_stats = 1 OR VALUES(seen_in_stats) = 1, 1, 0),
                      name = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name)
                        ELSE name
                      END,
                      name_lc = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name_lc)
                        ELSE name_lc
                      END,
                      name_source = CASE
                        WHEN VALUES(name_priority) >= name_priority THEN VALUES(name_source)
                        ELSE name_source
                      END,
                      name_priority = GREATEST(name_priority, VALUES(name_priority))
                    """;
        } else {
            updateKnownSql = null;
        }

        try (PreparedStatement updateResolved = connection.prepareStatement(updateSuccessSql);
             PreparedStatement updateChecked = hasNameCheckedAt
                     ? connection.prepareStatement(
                     "UPDATE player_profile SET name_checked_at=NOW() WHERE run_id=? AND uuid=?"
             )
                     : null;
             PreparedStatement updateKnown = updateKnownSql != null
                     ? connection.prepareStatement(updateKnownSql)
                     : null;
             PreparedStatement updateKnownChecked = hasKnownNameCheckedAt
                     ? connection.prepareStatement("UPDATE player_known SET name_checked_at=NOW() WHERE uuid=?")
                     : null) {
            boolean stopRequested = false;

            for (int i = 0; i < profileCandidates.size(); i++) {
                if (shuttingDown.get()) {
                    skipped += totalCandidates - processed;
                    stopRequested = true;
                    break;
                }

                NameCandidate candidate = profileCandidates.get(i);
                String resolvedName = lookupNameViaMojang(candidate.uuid());
                if (resolvedName != null) {
                    updateResolved.setString(1, resolvedName);
                    updateResolved.setString(2, resolvedName.toLowerCase(Locale.ROOT));
                    updateResolved.setLong(3, runId);
                    updateResolved.setBytes(4, UuidCodec.toBytes(candidate.uuid()));
                    updateResolved.addBatch();
                    batchState.markUpdatedProfileRow();
                    if (updateKnown != null) {
                        addKnownNameUpdateBatch(updateKnown, candidate.uuid(), resolvedName, true);
                        batchState.markUpdatedKnownRow();
                    }
                    resolved++;
                } else {
                    failed++;
                    if (updateChecked != null) {
                        updateChecked.setLong(1, runId);
                        updateChecked.setBytes(2, UuidCodec.toBytes(candidate.uuid()));
                        updateChecked.addBatch();
                        batchState.markCheckedProfileRow();
                    }
                    if (updateKnownChecked != null) {
                        updateKnownChecked.setBytes(1, UuidCodec.toBytes(candidate.uuid()));
                        updateKnownChecked.addBatch();
                        batchState.markCheckedKnownRow();
                    }
                }
                processed++;

                flushNameResolverBatchesIfNeeded(
                        connection,
                        updateResolved,
                        updateChecked,
                        updateKnown,
                        updateKnownChecked,
                        batchState,
                        false
                );

                if (sleepMs > 0 && processed < totalCandidates) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        skipped += totalCandidates - processed;
                        stopRequested = true;
                        break;
                    }
                }
            }

            if (!stopRequested) {
                for (int i = 0; i < knownCandidates.size(); i++) {
                    if (shuttingDown.get()) {
                        skipped += totalCandidates - processed;
                        stopRequested = true;
                        break;
                    }

                    NameCandidate candidate = knownCandidates.get(i);
                    String resolvedName = lookupNameViaMojang(candidate.uuid());
                    if (resolvedName != null) {
                        if (updateKnown != null) {
                            addKnownNameUpdateBatch(updateKnown, candidate.uuid(), resolvedName, false);
                            batchState.markUpdatedKnownRow();
                        }
                        resolved++;
                    } else {
                        failed++;
                        if (updateKnownChecked != null) {
                            updateKnownChecked.setBytes(1, UuidCodec.toBytes(candidate.uuid()));
                            updateKnownChecked.addBatch();
                            batchState.markCheckedKnownRow();
                        }
                    }
                    processed++;

                    flushNameResolverBatchesIfNeeded(
                            connection,
                            updateResolved,
                            updateChecked,
                            updateKnown,
                            updateKnownChecked,
                            batchState,
                            false
                    );

                    if (sleepMs > 0 && processed < totalCandidates) {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            skipped += totalCandidates - processed;
                            stopRequested = true;
                            break;
                        }
                    }
                }
            }

            flushNameResolverBatchesIfNeeded(
                    connection,
                    updateResolved,
                    updateChecked,
                    updateKnown,
                    updateKnownChecked,
                    batchState,
                    true
            );
        }

        int backfilled = hasPlayerKnown ? ensureKnownNamesPresent(connection) : 0;
        if (backfilled > 0) {
            connection.commit();
        }

        logger.info("Name resolver finished: candidates=" + totalCandidates
                + ", resolved=" + resolved
                + ", failed=" + failed
                + ", skipped=" + skipped
                + ", backfilled=" + backfilled);
        return new NameResolverResult(totalCandidates, resolved, failed, skipped);
    }

    private List<NameCandidate> loadNameResolverCandidates(
            Connection connection,
            long runId,
            boolean hasNameSource,
            boolean hasNameCheckedAt,
            int maxCandidates
    ) throws SQLException {
        String missingExpr = "(name IS NULL OR name='' OR name='Unknown' OR name REGEXP '^[0-9a-f]{12}$')";
        String criticalExpr = hasNameSource
                ? "((name_source IN ('fallback','unknown')) OR " + missingExpr + ")"
                : missingExpr;

        boolean applyRefreshWindow = hasNameCheckedAt && settings.nameResolverRefreshDays() > 0;
        Timestamp refreshCutoff = null;
        String staleExpr = "FALSE";
        if (applyRefreshWindow) {
            refreshCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(settings.nameResolverRefreshDays())));
            staleExpr = "(name_checked_at IS NULL OR name_checked_at < ?)";
        }

        String whereExpr = applyRefreshWindow
                ? "(" + criticalExpr + " OR " + staleExpr + ")"
                : "(" + criticalExpr + ")";
        String orderBy = " ORDER BY CASE WHEN " + criticalExpr + " THEN 0 ELSE 1 END";
        if (hasNameCheckedAt) {
            orderBy += ", COALESCE(name_checked_at, '1970-01-01'), uuid ASC";
        } else {
            orderBy += ", uuid ASC";
        }
        String sql = "SELECT uuid FROM player_profile WHERE run_id=? AND "
                + whereExpr
                + orderBy
                + " LIMIT ?";

        List<NameCandidate> out = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int idx = 1;
            stmt.setLong(idx++, runId);
            if (applyRefreshWindow) {
                stmt.setTimestamp(idx++, refreshCutoff);
            }
            stmt.setInt(idx, Math.max(1, maxCandidates));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byte[] uuidBytes = rs.getBytes(1);
                    if (uuidBytes == null || uuidBytes.length != 16) {
                        continue;
                    }
                    out.add(new NameCandidate(UuidCodec.fromBytes(uuidBytes)));
                }
            }
        }
        return out;
    }

    private List<NameCandidate> loadKnownNameResolverCandidates(
            Connection connection,
            Set<UUID> excludedUuids,
            int limit,
            boolean hasKnownNameCheckedAt
    ) throws SQLException {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        String criticalExpr = "(name IS NULL OR name='' OR name_source IN ('fallback','unknown') OR name REGEXP '^[0-9a-f]{12}$')";
        boolean applyRefreshWindow = hasKnownNameCheckedAt && settings.nameResolverRefreshDays() > 0;
        Timestamp refreshCutoff = null;
        String staleExpr = "FALSE";
        if (applyRefreshWindow) {
            refreshCutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(settings.nameResolverRefreshDays())));
            staleExpr = "(name_checked_at IS NULL OR name_checked_at < ?)";
        }
        String whereExpr = applyRefreshWindow
                ? "(" + criticalExpr + " OR " + staleExpr + ")"
                : "(" + criticalExpr + ")";
        String orderBy = " ORDER BY CASE WHEN " + criticalExpr + " THEN 0 ELSE 1 END";
        if (hasKnownNameCheckedAt) {
            orderBy += ", COALESCE(name_checked_at, '1970-01-01'), last_seen DESC, uuid ASC";
        } else {
            orderBy += ", last_seen DESC, uuid ASC";
        }
        String sql = "SELECT uuid FROM player_known WHERE " + whereExpr + orderBy + " LIMIT ?";

        int fetchLimit = Math.min(5000, limit + Math.min(2000, excludedUuids.size()));
        List<NameCandidate> out = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int idx = 1;
            if (applyRefreshWindow) {
                stmt.setTimestamp(idx++, refreshCutoff);
            }
            stmt.setInt(idx, Math.max(1, fetchLimit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    byte[] uuidBytes = rs.getBytes(1);
                    if (uuidBytes == null || uuidBytes.length != 16) {
                        continue;
                    }
                    UUID uuid = UuidCodec.fromBytes(uuidBytes);
                    if (excludedUuids.contains(uuid)) {
                        continue;
                    }
                    out.add(new NameCandidate(uuid));
                }
            }
        }
        return out;
    }

    private boolean ensureKnownNameCheckedAtColumn(Connection connection) throws SQLException {
        if (!tableHasColumn(connection, "player_known", "name_checked_at")) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "ALTER TABLE player_known ADD COLUMN name_checked_at DATETIME DEFAULT NULL"
            )) {
                stmt.execute();
                connection.commit();
            } catch (SQLException ex) {
                if (!tableHasColumn(connection, "player_known", "name_checked_at")) {
                    logger.log(Level.WARNING, "Could not add player_known.name_checked_at column.", ex);
                    return false;
                }
            }
        }
        return tableHasColumn(connection, "player_known", "name_checked_at");
    }

    private String lookupNameViaMojang(UUID uuid) {
        String undashedUuid = uuid.toString().replace("-", "");
        String sessionName = fetchNameFromSessionServer(undashedUuid, uuid);
        if (sessionName != null) {
            return sessionName;
        }
        return fetchNameFromNameHistory(undashedUuid, uuid);
    }

    private String fetchNameFromSessionServer(String undashedUuid, UUID originalUuid) {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + undashedUuid;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.nameResolverRequestTimeoutMs()))
                .header("User-Agent", NAME_RESOLVER_USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = nameResolverHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode nameNode = root.get("name");
                if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                    return trimName(nameNode.asText());
                }
                return null;
            }
            if (status == 404 || status == 204) {
                return null;
            }
            logger.fine("Name resolver sessionserver returned HTTP " + status + " for " + originalUuid);
            return null;
        } catch (Exception ex) {
            logger.log(Level.FINE, "Name resolver sessionserver request failed for " + originalUuid, ex);
            return null;
        }
    }

    private String fetchNameFromNameHistory(String undashedUuid, UUID originalUuid) {
        String url = "https://api.mojang.com/user/profiles/" + undashedUuid + "/names";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.nameResolverRequestTimeoutMs()))
                .header("User-Agent", NAME_RESOLVER_USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = nameResolverHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (!root.isArray() || root.isEmpty()) {
                    return null;
                }
                JsonNode last = root.get(root.size() - 1);
                JsonNode nameNode = last.get("name");
                if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                    return trimName(nameNode.asText());
                }
                return null;
            }
            if (status == 404 || status == 204) {
                return null;
            }
            logger.fine("Name resolver mojang history returned HTTP " + status + " for " + originalUuid);
            return null;
        } catch (Exception ex) {
            logger.log(Level.FINE, "Name resolver mojang history request failed for " + originalUuid, ex);
            return null;
        }
    }

    private ProfileRow buildProfileRow(
            long runId,
            UUID uuid,
            Map<UUID, String> usercacheNames,
            Map<UUID, PlayerProfileMeta> existingProfiles,
            boolean hasNameSource,
            boolean hasNameCheckedAt
    ) {
        String name;
        String source;
        Timestamp checkedAt;

        String fromUsercache = usercacheNames.get(uuid);
        // Name priority: usercache -> previous profile -> deterministic UUID fallback.
        if (fromUsercache != null && !fromUsercache.isBlank()) {
            name = trimName(fromUsercache);
            source = "usercache";
            PlayerProfileMeta meta = existingProfiles.get(uuid);
            checkedAt = hasNameCheckedAt && meta != null ? meta.nameCheckedAt() : null;
        } else {
            PlayerProfileMeta meta = existingProfiles.get(uuid);
            if (meta != null && meta.name() != null && !meta.name().isBlank()) {
                name = trimName(meta.name());
                source = meta.nameSource() == null ? "unknown" : meta.nameSource();
                checkedAt = hasNameCheckedAt ? meta.nameCheckedAt() : null;
            } else {
                name = fallbackNameFromUuid(uuid);
                source = "fallback";
                checkedAt = null;
            }
        }

        String normalizedSource = hasNameSource ? source : null;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return new ProfileRow(runId, uuid, name, name.toLowerCase(Locale.ROOT), normalizedSource, checkedAt, now);
    }

    private String trimName(String name) {
        String trimmed = name.trim();
        if (trimmed.length() <= 16) {
            return trimmed;
        }
        return trimmed.substring(0, 16);
    }

    private static String fallbackNameFromUuid(UUID uuid) {
        return uuid.toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractStatsMap(Map<String, Object> root) {
        Object statsNode = root.get("stats");
        if (statsNode instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private void stripWallBanners(Map<String, Object> stats) {
        // Some versions exposed per-color wall banner stats; removing them keeps metric history stable.
        for (Map.Entry<String, Object> sectionEntry : stats.entrySet()) {
            Object maybeMap = sectionEntry.getValue();
            if (!(maybeMap instanceof Map<?, ?> section)) {
                continue;
            }
            List<String> toDelete = new ArrayList<>();
            for (Map.Entry<?, ?> statEntry : section.entrySet()) {
                Object key = statEntry.getKey();
                if (key instanceof String keyText && keyText.endsWith("_wall_banner")) {
                    toDelete.add(keyText);
                }
            }
            for (String key : toDelete) {
                ((Map<String, Object>) section).remove(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private long nestedLong(Map<String, Object> root, String sectionKey, String statKey) {
        Object maybeSection = root.get(sectionKey);
        if (!(maybeSection instanceof Map<?, ?> section)) {
            return 0L;
        }
        Object value = ((Map<String, Object>) section).get(statKey);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private byte[] sha1(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 algorithm missing", ex);
        }
    }

    private UUID parseUuidFromStatsFilename(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - 5);
        try {
            return UuidCodec.parseFlexible(stem);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Path resolveStatsDir() {
        String configured = settings.statsDirectory();
        if (!isAuto(configured)) {
            return Path.of(configured);
        }

        Path worldContainer = plugin.getServer().getWorldContainer().toPath();
        List<Path> worldStats = plugin.getServer().getWorlds().stream()
                .map(world -> world.getWorldFolder().toPath().resolve("stats"))
                .distinct()
                .collect(Collectors.toList());

        for (Path candidate : worldStats) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        Path vanillaDefault = worldContainer.resolve("world").resolve("stats");
        if (Files.isDirectory(vanillaDefault)) {
            return vanillaDefault;
        }

        if (!worldStats.isEmpty()) {
            return worldStats.get(0);
        }
        return vanillaDefault;
    }

    private Path resolveUsercachePath() {
        String configured = settings.usercachePath();
        if (!isAuto(configured)) {
            return Path.of(configured);
        }
        return plugin.getServer().getWorldContainer().toPath().resolve("usercache.json");
    }

    private Path resolveBannedPlayersPath() {
        String configured = settings.bannedPlayersPath();
        if (!isAuto(configured)) {
            return Path.of(configured);
        }
        return plugin.getServer().getWorldContainer().toPath().resolve("banned-players.json");
    }

    private boolean isAuto(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || "auto".equals(normalized)
                || "default".equals(normalized)
                || "standard".equals(normalized)
                || "leer".equals(normalized);
    }

    private record ProfileRow(
            long runId,
            UUID uuid,
            String name,
            String nameLc,
            String nameSource,
            Timestamp nameCheckedAt,
            Timestamp lastSeen
    ) {
    }

    private record StatsRow(
            UUID uuid,
            byte[] statsGzip,
            byte[] statsSha1
    ) {
    }

    private record BanEntry(
            UUID uuid,
            String name,
            String nameLc,
            String reason,
            String bannedBy,
            Timestamp bannedAt,
            Timestamp expiresAt,
            boolean permanent,
            String sourceRaw,
            String expiresRaw
    ) {
    }

    private record BanFileLoadResult(
            boolean syncable,
            List<BanEntry> entries,
            String note
    ) {
    }

    private record BanSyncResult(
            String note
    ) {
    }

    private record KnownPlayerRow(
            UUID uuid,
            String name,
            String nameLc,
            String nameSource,
            int namePriority,
            boolean seenInStats,
            boolean seenInUsercache,
            boolean seenInBans
    ) {
    }

    private record KnownSyncResult(
            String note
    ) {
    }

    private static final class KnownPlayerAccumulator {
        private final UUID uuid;
        private String name;
        private String nameLc;
        private String nameSource;
        private int namePriority;
        private boolean seenInStats;
        private boolean seenInUsercache;
        private boolean seenInBans;

        private KnownPlayerAccumulator(UUID uuid) {
            this.uuid = uuid;
            this.nameSource = "unknown";
            this.namePriority = KNOWN_NAME_PRIORITY_UNKNOWN;
        }

        private UUID uuid() {
            return uuid;
        }

        private String name() {
            return name;
        }

        private String nameLc() {
            return nameLc;
        }

        private String nameSource() {
            return nameSource;
        }

        private int namePriority() {
            return namePriority;
        }

        private boolean seenInStats() {
            return seenInStats;
        }

        private boolean seenInUsercache() {
            return seenInUsercache;
        }

        private boolean seenInBans() {
            return seenInBans;
        }

        private void markSeenInStats() {
            this.seenInStats = true;
        }

        private void markSeenInUsercache() {
            this.seenInUsercache = true;
        }

        private void markSeenInBans() {
            this.seenInBans = true;
        }

        private void ensureNamePresent() {
            if (this.name != null && !this.name.isBlank()) {
                return;
            }
            String fallback = fallbackNameFromUuid(this.uuid);
            this.name = fallback;
            this.nameLc = fallback;
            this.nameSource = "fallback";
            if (this.namePriority < KNOWN_NAME_PRIORITY_FALLBACK) {
                this.namePriority = KNOWN_NAME_PRIORITY_FALLBACK;
            }
        }

        private void considerName(String candidateName, String source, int priority) {
            if (candidateName == null || candidateName.isBlank()) {
                return;
            }
            if (priority < this.namePriority) {
                return;
            }
            this.name = candidateName;
            this.nameLc = candidateName.toLowerCase(Locale.ROOT);
            this.nameSource = source;
            this.namePriority = priority;
        }
    }

    private record AwardRow(
            long runId,
            String metricId,
            int place,
            UUID uuid,
            long value,
            int points
    ) {
    }

    private static final class NameResolverBatchState {
        private int pendingUpdatedProfileRows;
        private int pendingUpdatedKnownRows;
        private int pendingCheckedProfileRows;
        private int pendingCheckedKnownRows;
        private Instant lastFlushAt = Instant.now();

        private void markUpdatedProfileRow() {
            pendingUpdatedProfileRows++;
        }

        private void markUpdatedKnownRow() {
            pendingUpdatedKnownRows++;
        }

        private void markCheckedProfileRow() {
            pendingCheckedProfileRows++;
        }

        private void markCheckedKnownRow() {
            pendingCheckedKnownRows++;
        }

        private int pendingRows() {
            return pendingUpdatedProfileRows
                    + pendingUpdatedKnownRows
                    + pendingCheckedProfileRows
                    + pendingCheckedKnownRows;
        }

        private boolean hasPendingRows() {
            return pendingRows() > 0;
        }

        private Instant lastFlushAt() {
            return lastFlushAt;
        }

        private void markFlushed() {
            pendingUpdatedProfileRows = 0;
            pendingUpdatedKnownRows = 0;
            pendingCheckedProfileRows = 0;
            pendingCheckedKnownRows = 0;
            lastFlushAt = Instant.now();
        }
    }

    private record NameCandidate(UUID uuid) {
    }

    private record NameResolverResult(
            int candidates,
            int resolved,
            int failed,
            int skipped
    ) {
    }
}
