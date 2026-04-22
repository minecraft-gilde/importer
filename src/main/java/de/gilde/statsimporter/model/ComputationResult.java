package de.gilde.statsimporter.model;

import java.util.List;
import java.util.UUID;

public record ComputationResult(
        UUID uuid,
        byte[] statsGzip,
        byte[] statsSha1,
        List<MetricValueRow> metricRows
) {
}

