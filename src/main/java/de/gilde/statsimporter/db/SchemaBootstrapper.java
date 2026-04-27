package de.gilde.statsimporter.db;

import de.gilde.statsimporter.config.PluginSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchemaBootstrapper {

    private final JavaPlugin plugin;
    private final javax.sql.DataSource dataSource;
    private final PluginSettings.BootstrapSettings settings;
    private final Set<String> protectedMetricIds;
    private final Logger logger;

    public SchemaBootstrapper(
            JavaPlugin plugin,
            javax.sql.DataSource dataSource,
            PluginSettings.BootstrapSettings settings,
            Set<String> protectedMetricIds
    ) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.settings = settings;
        this.protectedMetricIds = protectedMetricIds == null ? Set.of() : Set.copyOf(protectedMetricIds);
        this.logger = plugin.getLogger();
    }

    public void ensureReady() throws Exception {
        if (!settings.autoSchema() && !settings.verifySchema()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            SchemaValidation initialValidation = validateSchema(connection);
            boolean schemaMissingOrInvalid = !initialValidation.missingRequirements().isEmpty();

            if (schemaMissingOrInvalid && settings.autoSchema()) {
                logger.warning("Schema missing/incomplete. Applying bundled db/schema.sql");
                executeSqlResource(connection, "db/schema.sql");
                connection.commit();
            }

            if (settings.verifySchema()) {
                // Validate again after potential auto-migration so startup fails fast on partial schema states.
                SchemaValidation validated = validateSchema(connection);
                if (!validated.missingRequirements().isEmpty()) {
                    throw new IllegalStateException("Schema validation failed. Missing: "
                            + String.join(", ", validated.missingRequirements()));
                }
            }

            boolean shouldSeed = (schemaMissingOrInvalid && settings.seedOnMissingSchema())
                    || (settings.seedIfMetricDefEmpty() && countMetricDefRows(connection) == 0)
                    || settings.syncSeeds();
            if (shouldSeed) {
                Path seedPath = resolveSeedFile();
                MetricSeeds seeds = readSeeds(seedPath);
                upsertSeeds(connection, seeds);
                if (settings.syncSeeds()) {
                    syncSeeds(connection, seeds);
                }
                connection.commit();
                logger.info("Seed import finished: metric_def=" + seeds.metricDefinitions().size()
                        + ", metric_source=" + seeds.metricSources().size());
            }
        }
    }

    private Path resolveSeedFile() throws IOException {
        String configured = settings.seedFile();
        String normalized = configured == null ? "" : configured.trim();
        if (normalized.isEmpty()) {
            normalized = "metric-seeds.yml";
        }

        Path path = Path.of(normalized);
        if (!path.isAbsolute()) {
            path = plugin.getDataFolder().toPath().resolve(normalized);
        }

        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            if ("metric-seeds.yml".equals(path.getFileName().toString())) {
                plugin.saveResource("metric-seeds.yml", false);
            }
        }

        if (!Files.exists(path)) {
            throw new IllegalStateException("Seed file not found: " + path);
        }
        return path;
    }

    private void upsertSeeds(Connection connection, MetricSeeds seeds) throws SQLException {
        String defSql = """
                INSERT INTO metric_def (id, label, category, unit, sort_order, enabled, divisor, decimals)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  label = VALUES(label),
                  category = VALUES(category),
                  unit = VALUES(unit),
                  sort_order = VALUES(sort_order),
                  enabled = VALUES(enabled),
                  divisor = VALUES(divisor),
                  decimals = VALUES(decimals)
                """;

        try (PreparedStatement stmt = connection.prepareStatement(defSql)) {
            for (MetricDefinitionSeed seed : seeds.metricDefinitions()) {
                stmt.setString(1, seed.id());
                stmt.setString(2, seed.label());
                stmt.setString(3, seed.category());
                stmt.setString(4, seed.unit());
                stmt.setInt(5, seed.sortOrder());
                stmt.setInt(6, seed.enabled() ? 1 : 0);
                stmt.setLong(7, seed.divisor());
                stmt.setInt(8, seed.decimals());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        String sourceSql = """
                INSERT INTO metric_source (metric_id, section, mc_key, weight)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE weight = VALUES(weight)
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sourceSql)) {
            for (MetricSourceSeed seed : seeds.metricSources()) {
                stmt.setString(1, seed.metricId());
                stmt.setString(2, seed.section());
                stmt.setString(3, seed.mcKey());
                stmt.setInt(4, seed.weight());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void syncSeeds(Connection connection, MetricSeeds seeds) throws SQLException {
        Set<String> seededMetricIds = new HashSet<>();
        for (MetricDefinitionSeed seed : seeds.metricDefinitions()) {
            seededMetricIds.add(seed.id());
        }

        deleteRemovedMetricSources(connection, seeds.metricSources());
        disableRemovedMetricDefinitions(connection, seededMetricIds);
    }

    private void deleteRemovedMetricSources(Connection connection, List<MetricSourceSeed> sources) throws SQLException {
        if (sources.isEmpty()) {
            try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM metric_source")) {
                stmt.executeUpdate();
            }
            return;
        }

        String keepPredicate = String.join(
                " OR ",
                Collections.nCopies(sources.size(), "(metric_id=? AND section=? AND mc_key=?)")
        );
        String sql = "DELETE FROM metric_source WHERE NOT (" + keepPredicate + ")";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int idx = 1;
            for (MetricSourceSeed source : sources) {
                stmt.setString(idx++, source.metricId());
                stmt.setString(idx++, source.section());
                stmt.setString(idx++, source.mcKey());
            }
            stmt.executeUpdate();
        }
    }

    private void disableRemovedMetricDefinitions(Connection connection, Set<String> seededMetricIds) throws SQLException {
        Set<String> keepIds = new HashSet<>(seededMetricIds);
        keepIds.addAll(protectedMetricIds);
        keepIds.removeIf(id -> id == null || id.isBlank());

        if (keepIds.isEmpty()) {
            try (PreparedStatement stmt = connection.prepareStatement("UPDATE metric_def SET enabled=0")) {
                stmt.executeUpdate();
            }
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(keepIds.size(), "?"));
        String sql = "UPDATE metric_def SET enabled=0 WHERE id NOT IN (" + placeholders + ")";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int idx = 1;
            for (String id : keepIds) {
                stmt.setString(idx++, id);
            }
            stmt.executeUpdate();
        }
    }

    private MetricSeeds readSeeds(Path path) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        List<MetricDefinitionSeed> definitions = new ArrayList<>();
        List<MetricSourceSeed> sources = new ArrayList<>();

        List<Map<?, ?>> rawDefs = asMapList(yaml.getList("metric-definitions"));
        for (Map<?, ?> raw : rawDefs) {
            String id = str(raw.get("id"));
            String label = str(raw.get("label"));
            if (id.isBlank() || label.isBlank()) {
                continue;
            }
            definitions.add(new MetricDefinitionSeed(
                    id,
                    label,
                    strOrDefault(raw.get("category"), "Allgemein"),
                    nullableStr(raw.get("unit")),
                    intOrDefault(raw.get("sort-order"), 0),
                    boolOrDefault(raw.get("enabled"), true),
                    longOrDefault(raw.get("divisor"), 1L),
                    intOrDefault(raw.get("decimals"), 0)
            ));
        }

        List<Map<?, ?>> rawSources = asMapList(yaml.getList("metric-sources"));
        for (Map<?, ?> raw : rawSources) {
            String metricId = str(raw.get("metric-id"));
            String section = str(raw.get("section"));
            String mcKey = str(raw.get("mc-key"));
            if (metricId.isBlank() || section.isBlank() || mcKey.isBlank()) {
                continue;
            }
            sources.add(new MetricSourceSeed(
                    metricId,
                    section,
                    mcKey,
                    intOrDefault(raw.get("weight"), 1)
            ));
        }

        return new MetricSeeds(definitions, sources);
    }

    private List<Map<?, ?>> asMapList(List<?> value) {
        List<Map<?, ?>> output = new ArrayList<>();
        if (value == null) {
            return output;
        }
        for (Object item : value) {
            if (item instanceof Map<?, ?> map) {
                output.add(map);
            }
        }
        return output;
    }

    private String str(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private String strOrDefault(Object value, String fallback) {
        String s = str(value);
        return s.isBlank() ? fallback : s;
    }

    private String nullableStr(Object value) {
        String s = str(value);
        return s.isBlank() ? null : s;
    }

    private int intOrDefault(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longOrDefault(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(str(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean boolOrDefault(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = str(value).toLowerCase(Locale.ROOT);
        if ("true".equals(raw) || "1".equals(raw) || "yes".equals(raw)) {
            return true;
        }
        if ("false".equals(raw) || "0".equals(raw) || "no".equals(raw)) {
            return false;
        }
        return fallback;
    }

    private long countMetricDefRows(Connection connection) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM metric_def");
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong(1);
        }
    }

    private void executeSqlResource(Connection connection, String resourcePath) throws IOException, SQLException {
        String sql = readResource(resourcePath);
        List<String> statements = splitStatements(sql);
        try (Statement statement = connection.createStatement()) {
            for (String part : statements) {
                statement.execute(part);
            }
        }
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("--")) {
                        continue;
                    }
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
    }

    private List<String> splitStatements(String sql) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // Tiny SQL splitter: semicolons inside quoted strings must not terminate statements.
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            }
            if (c == ';' && !inString) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    parts.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private SchemaValidation validateSchema(Connection connection) throws SQLException {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("import_run", List.of("id", "generated_at", "status"));
        required.put("site_state", List.of("id", "active_run_id", "updated_at"));
        required.put("player_profile", List.of("run_id", "uuid", "name", "name_lc", "last_seen"));
        required.put("player_known", List.of("uuid", "name", "name_lc", "name_source", "name_priority", "first_seen", "last_seen", "seen_in_stats", "seen_in_usercache", "seen_in_bans"));
        required.put("player_ban", List.of("run_id", "uuid", "name", "name_lc", "reason", "banned_by", "banned_at", "expires_at", "is_permanent"));
        required.put("player_stats", List.of("run_id", "uuid", "stats_gzip", "stats_sha1", "updated_at"));
        required.put("metric_def", List.of("id", "label", "category", "unit", "sort_order", "enabled", "divisor", "decimals"));
        required.put("metric_source", List.of("metric_id", "section", "mc_key", "weight"));
        required.put("metric_value", List.of("run_id", "metric_id", "uuid", "value"));

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> table : required.entrySet()) {
            if (!tableExists(connection, table.getKey())) {
                missing.add("table:" + table.getKey());
                continue;
            }
            for (String column : table.getValue()) {
                if (!columnExists(connection, table.getKey(), column)) {
                    missing.add("column:" + table.getKey() + "." + column);
                }
            }
        }
        return new SchemaValidation(missing);
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

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
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

    private record SchemaValidation(List<String> missingRequirements) {
    }

    private record MetricSeeds(
            List<MetricDefinitionSeed> metricDefinitions,
            List<MetricSourceSeed> metricSources
    ) {
    }

    private record MetricDefinitionSeed(
            String id,
            String label,
            String category,
            String unit,
            int sortOrder,
            boolean enabled,
            long divisor,
            int decimals
    ) {
    }

    private record MetricSourceSeed(
            String metricId,
            String section,
            String mcKey,
            int weight
    ) {
    }
}
