package de.gilde.statsimporter.model;

import java.time.Duration;
import java.time.Instant;

public record ImportSummary(
        Instant startedAt,
        Instant finishedAt,
        boolean success,
        String reason,
        String message,
        int processedFiles,
        int keptPlayers,
        int changedPlayers
) {

    public static ImportSummary neverRan() {
        Instant now = Instant.now();
        return new ImportSummary(now, now, false, "none", "No import run yet", 0, 0, 0);
    }

    public long durationSeconds() {
        return Math.max(0L, Duration.between(startedAt, finishedAt).getSeconds());
    }
}

