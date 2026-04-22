package de.gilde.statsimporter.model;

import java.sql.Timestamp;

public record PlayerProfileMeta(
        String name,
        String nameSource,
        Timestamp nameCheckedAt
) {
}

