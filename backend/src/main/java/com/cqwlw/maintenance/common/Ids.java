package com.cqwlw.maintenance.common;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.Base64;

public final class Ids {

    /** originalRecordId 同毫秒内序号（保证任意连续 100 个 ID 互不相同） */
    private static final AtomicInteger RECORD_SEQ = new AtomicInteger();

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
        int seq = Math.floorMod(RECORD_SEQ.getAndIncrement(), 100);
        return "1948" + System.currentTimeMillis() + String.format("%02d", seq);
    }
}
