package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.Ids;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * originalRecordId 生成口径（docs/01 待确认#10：我方暂定 19 位；平台 2026-09-30 实测接受 19 位）。
 * 23 位会超出 19 位口径并可能对端 BIGINT 溢出 → 上报被拒/截断即监管数据丢失（AGENTS §6）。
 */
class IdsTest {

    @Test
    void recordIdIsNineteenDigits() {
        for (int i = 0; i < 50; i++) {
            String id = Ids.nextRecordId();
            assertEquals(19, id.length(), "originalRecordId 必须 19 位，实际=" + id);
            assertTrue(id.matches("\\d{19}"), "originalRecordId 必须为纯数字，实际=" + id);
            assertTrue(id.startsWith("1948"), "保留既有 1948 前缀（docs/04 B.6 示例 1948xxxx），实际=" + id);
        }
    }

    /** 同毫秒内序号不重复：任意连续 100 个 ID 互不相同（签退为人工节拍，远低于该上限） */
    @Test
    void consecutiveRecordIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(Ids.nextRecordId());
        }
        assertEquals(100, ids.size(), "连续 100 个 originalRecordId 应互不相同");
    }

    @Test
    void businessIdKeepsPrefixStyle() {
        String id = Ids.next("wo");
        assertTrue(id.startsWith("wo_") && id.length() > 10, "业务ID前缀风格应与 mock 一致，实际=" + id);
    }

    /** 业务表 id 列最小 VARCHAR(32)；修复前 hex UUID 32 位 + 前缀超长，生产 INSERT use_unit 报错 */
    @Test
    void businessIdFitsIdColumnWidth() {
        String[] prefixes = {"op", "sess", "uu", "emp", "el", "pl", "wo", "pd", "msg",
                "rs", "ft", "dr", "in", "file", "nt", "ul", "chk", "ur"};
        for (String p : prefixes) {
            for (int i = 0; i < 20; i++) {
                String id = Ids.next(p);
                assertTrue(id.length() <= 32, "业务ID超长(>" + 32 + ")：" + id + " 长度=" + id.length());
                assertTrue(id.startsWith(p + "_"), "业务ID前缀风格异常：" + id);
            }
        }
    }
}
