package de.gilde.statsimporter.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.gilde.statsimporter.model.ComputationResult;
import de.gilde.statsimporter.model.MetricSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatsCalculatorTest {

    @Test
    void computesWeightedMetricsAndStoresHash() throws Exception {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        byte[] canonicalJson = "{\"stats\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] sha1 = new byte[20];
        sha1[0] = 7;
        Map<String, Object> stats = Map.of(
                "minecraft:custom", Map.of("minecraft:jump", "4"),
                "minecraft:mined", Map.of("minecraft:stone", 3)
        );
        Map<String, List<MetricSource>> sources = Map.of(
                "weighted_score", List.of(
                        new MetricSource("weighted_score", "minecraft:custom", "minecraft:jump", 2),
                        new MetricSource("weighted_score", "minecraft:mined", "minecraft:stone", 5)
                )
        );

        ComputationResult result = new StatsCalculator().compute(uuid, canonicalJson, sha1, stats, sources);

        assertEquals(uuid, result.uuid());
        assertArrayEquals(sha1, result.statsSha1());
        assertFalse(result.statsGzip().length == 0);
        assertEquals(1, result.metricRows().size());
        assertEquals("weighted_score", result.metricRows().get(0).metricId());
        assertEquals(23L, result.metricRows().get(0).value());
    }
}
