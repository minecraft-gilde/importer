package de.gilde.statsimporter.importer;

import de.gilde.statsimporter.model.ComputationResult;
import de.gilde.statsimporter.model.MetricSource;
import de.gilde.statsimporter.model.MetricValueRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class StatsCalculator {

    public ComputationResult compute(
            UUID uuid,
            byte[] canonicalStatsJson,
            byte[] sha1,
            Map<String, Object> stats,
            Map<String, List<MetricSource>> metricSources
    ) throws IOException {
        byte[] gzip = gzip(canonicalStatsJson);
        List<MetricValueRow> metricRows = new ArrayList<>();
        // Reuse section maps across multiple sources to avoid repeated map lookups/casts.
        Map<String, Map<String, Object>> sectionCache = new HashMap<>();

        for (Map.Entry<String, List<MetricSource>> entry : metricSources.entrySet()) {
            long total = 0L;
            for (MetricSource source : entry.getValue()) {
                Map<String, Object> sectionMap = sectionCache.computeIfAbsent(source.section(), section -> section(stats, section));
                long value = toLong(sectionMap.get(source.mcKey()));
                // Multiple source keys can contribute to one metric via configurable weights.
                total += value * source.weight();
            }
            if (total > 0L) {
                metricRows.add(new MetricValueRow(entry.getKey(), uuid, total));
            }
        }

        return new ComputationResult(uuid, gzip, sha1, metricRows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> stats, String section) {
        Object value = stats.get(section);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long toLong(Object value) {
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

    private byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(1024, input.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }
}
