package com.cqwlw.maintenance.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ID 生成：与 mock 前缀风格一致（wo_/ur_/msg_...）；
 * originalRecordId 为 19 位纯数字（平台 2.6 要求），基于时间戳 + 随机数。
 */
public final class Ids {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private Ids() {
    }

    public static String next(String prefix) {
        String t = LocalDateTime.now().format(TS);
        int rnd = ThreadLocalRandom.current().nextInt(1000, 9999);
        return prefix + "_" + t + rnd;
    }

    public static String nextRecordId() {
        long now = System.currentTimeMillis();
        int rnd = ThreadLocalRandom.current().nextInt(100000, 999999);
        return "1948" + now + rnd;
    }
}
