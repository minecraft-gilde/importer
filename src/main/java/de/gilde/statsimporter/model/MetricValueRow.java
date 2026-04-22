package de.gilde.statsimporter.model;

import java.util.UUID;

public record MetricValueRow(
        String metricId,
        UUID uuid,
        long value
) {
}

