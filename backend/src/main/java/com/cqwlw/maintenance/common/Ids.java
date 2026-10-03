package com.cqwlw.maintenance.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ID 生成：与 mock 前缀风格一致（wo_/ur_/msg_...）；
 * originalRecordId 为 19 位纯数字（平台 2.6 要求），基于时间戳 + 随机数。
 */
public final class Ids {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    /** originalRecordId 同毫秒内序号（保证任意连续 100 个 ID 互不相同） */
    private static final AtomicInteger RECORD_SEQ = new AtomicInteger();

    private Ids() {
    }

    public static String next(String prefix) {
        String t = LocalDateTime.now().format(TS);
        int rnd = ThreadLocalRandom.current().nextInt(1000, 9999);
        return prefix + "_" + t + rnd;
    }

    /**
     * originalRecordId = 19 位纯数字："1948" + 13 位毫秒 + 2 位同毫秒序号。
     * 口径：docs/01 待确认#10（我方暂定雪花ID 19 位）、docs/02 §5.2；平台 2026-09-30 实测接受的即 19 位值。
     * 原实现拼 6 位随机数得 23 位，超出 19 位口径并可能对端 BIGINT 溢出（上报被拒/截断即数据丢失，AGENTS §6）。
     * 唯一性：同毫秒内序号不重复 → 任意连续 100 个 ID 互不相同；签退为人工节拍，远低于该上限。
     */
    public static String nextRecordId() {
        int seq = Math.floorMod(RECORD_SEQ.getAndIncrement(), 100);
        return "1948" + System.currentTimeMillis() + String.format("%02d", seq);
    }
}
