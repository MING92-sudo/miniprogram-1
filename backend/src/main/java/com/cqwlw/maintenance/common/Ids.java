package com.cqwlw.maintenance.common;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * ID 生成：与 mock 前缀风格一致（wo_/ur_/msg_...）；
 * originalRecordId 为 19 位纯数字（平台 2.6 要求），基于时间戳 + 随机数。
 */
public final class Ids {

    /** originalRecordId 同毫秒内序号（保证任意连续 100 个 ID 互不相同） */
    private static final AtomicInteger RECORD_SEQ = new AtomicInteger();

    private Ids() {
    }

    public static String next(String prefix) {
        // C2：UUID 不可枚举——旧实现（秒级时间戳+9000 随机数）可被批量猜出文件 ID。
        // 但 32 位 hex UUID + 前缀会超过业务表 id 列 VARCHAR(32)：2026-11-11 生产 INSERT use_unit
        // 报 "Data too long for column 'id'"（"uu_" + 32 = 35 > 32）。改用 Base64URL 编码 16 字节 UUID
        // （22 字符，128bit 熵不变、仍不可枚举），prefix + "_" + 22 ≤ 32（最长前缀 sess/file 得 27）。
        UUID uuid = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
