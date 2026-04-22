package de.gilde.statsimporter.model;

public record MetricSource(
        String metricId,
        String section,
        String mcKey,
        int weight
) {
}

