package com.cqwlw.maintenance.common;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String nextRecordId() {
        long random = RANDOM.nextLong(1_000_000_000_000_000L);
        return "1948" + String.format(Locale.ROOT, "%015d", random);
    }
}
