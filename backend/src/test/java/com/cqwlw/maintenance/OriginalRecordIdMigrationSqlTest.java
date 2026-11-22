package com.cqwlw.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginalRecordIdMigrationSqlTest {

    @Test
    void migrationAddsUniqueGuardWithoutDeletingRows() throws Exception {
        ClassPathResource migration = new ClassPathResource("db/migration/V68__original_record_id_unique.sql");
        assertTrue(migration.exists(), "缺少 V68 originalRecordId 唯一性迁移");
        String sql = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(sql.matches("(?is).*ALTER\\s+TABLE\\s+work_order\\s+ADD\\s+UNIQUE\\s+KEY\\s+"
                        + "uk_work_order_original_record_id\\s*\\(\\s*original_record_id\\s*\\).*"),
                "V68 缺少工单原记录号唯一索引");
        assertTrue(sql.matches("(?is).*ALTER\\s+TABLE\\s+maintain_record\\s+ADD\\s+UNIQUE\\s+KEY\\s+"
                        + "uk_maintain_record_original_version\\s*\\(\\s*original_record_id\\s*,\\s*version\\s*\\).*"),
                "V68 必须允许不同修订版本共用原记录号，同时约束同一版本唯一");
        assertTrue(countMatches(sql, "(?i)HAVING\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s*>\\s*1") >= 2,
                "V68 必须分别预检工单与记录版本重复");
        assertFalse(sql.matches("(?is).*\\b(DELETE|UPDATE)\\b.*"),
                "V68 不得自动清理或改写业务数据");
    }

    private int countMatches(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
