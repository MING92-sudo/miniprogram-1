package com.cqwlw.maintenance.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V47 租户快照结构不变量（无测试库，按 classpath SQL 文本断言；spec §4）。 */
class V47SnapshotSqlTest {

    private String sql(String name) throws Exception {
        return new String(new ClassPathResource("db/migration/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void v47ContainsSnapshotColumnsIssueTableAndIndexes() throws Exception {
        String s = sql("V47__tenant_snapshot.sql");
        assertTrue(s.contains("ALTER TABLE maintain_record"), "缺 maintain_record 加列");
        assertTrue(s.contains("ALTER TABLE fault"), "缺 fault 加列");
        assertTrue(s.contains("ALTER TABLE work_order"), "缺 work_order 加列");
        assertTrue(s.contains("use_unit_id"), "缺 use_unit_id 快照列");
        assertTrue(s.contains("use_unit_name"), "缺 use_unit_name 快照列");
        assertTrue(s.contains("elevator_id"), "缺 maintain_record.elevator_id 快照列");
        assertTrue(s.contains("CREATE INDEX idx_record_unit"), "缺 idx_record_unit");
        assertTrue(s.contains("CREATE INDEX idx_fault_unit"), "缺 idx_fault_unit");
        assertTrue(s.contains("CREATE INDEX idx_order_unit"), "缺 idx_order_unit");
        assertTrue(s.contains("CREATE TABLE client_migration_issue"), "缺人工核对清单表");
        assertTrue(s.contains("decision_json"), "缺 decision_json 列");
        assertTrue(s.contains("UNIQUE KEY uk_issue (object_type, object_id, issue_type)"), "缺 uk_issue 唯一键");
        assertTrue(s.contains("KEY idx_issue_pending (resolution, issue_type)"), "缺 idx_issue_pending");
        assertTrue(s.contains("utf8mb4"), "建表须显式 utf8mb4（evidence/resolve_note 存中文，§19-23）");
    }

    @Test
    void v47HasNoDestructiveStatement() throws Exception {
        String up = sql("V47__tenant_snapshot.sql").toUpperCase();
        assertFalse(up.contains("DELETE"), "严禁自动 DELETE（留存红线）");
        assertFalse(up.contains("DROP"), "迁移不得删表/删列");
        assertFalse(up.contains("TRUNCATE"), "严禁自动 TRUNCATE");
    }
}
