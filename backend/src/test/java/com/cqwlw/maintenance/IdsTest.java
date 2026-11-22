package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.Ids;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recordIdDoesNotUseProcessLocalSequence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/cqwlw/maintenance/common/Ids.java"));
        assertFalse(source.contains("AtomicInteger"), "originalRecordId 不得依赖进程内序号");
        assertFalse(source.matches("(?s).*floorMod\\s*\\([^;]*100[^;]*;.*"),
                "originalRecordId 不得使用模 100 序号");
    }

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
