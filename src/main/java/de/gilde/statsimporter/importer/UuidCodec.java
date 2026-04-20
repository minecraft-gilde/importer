package de.gilde.statsimporter.importer;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;

public final class UuidCodec {

    private UuidCodec() {
    }

    public static byte[] toBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("UUID bytes must be exactly 16 bytes long");
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long msb = bb.getLong();
        long lsb = bb.getLong();
        return new UUID(msb, lsb);
    }

    public static UUID parseFlexible(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() == 32 && !normalized.contains("-")) {
            normalized = normalized.substring(0, 8) + "-"
                    + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        return UUID.fromString(normalized);
    }
}

