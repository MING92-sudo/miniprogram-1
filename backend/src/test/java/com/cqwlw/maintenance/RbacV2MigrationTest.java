package com.cqwlw.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V35/V36 结构不变量（无测试库，按 classpath SQL 文本断言；spec §7.2）。 */
class RbacV2MigrationTest {

    private String sql(String name) throws Exception {
        return new String(new ClassPathResource("db/migration/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void v35CreatesFiveTablesAndScopeVersion() throws Exception {
        String s = sql("V35__rbac_v2_schema.sql");
        for (String t : new String[]{"sys_org", "sys_user_role", "sys_role_scope", "sys_grant", "sys_audit_log"}) {
            assertTrue(s.contains("CREATE TABLE " + t), "缺表 " + t);
        }
        assertTrue(s.contains("ADD COLUMN scope_version INT NOT NULL DEFAULT 0"), "缺 scope_version 列");
        assertTrue(s.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), "建表约定不符");
        // 13 个角色范围种子
        for (String r : new String[]{"PLATFORM_ADMIN", "SYS_ADMIN", "INST_MGR", "INST_PM", "INST_CLERK",
                "INSTALLER", "MAINT_MGR", "MAINT_QUALITY", "MAINT_BIZ", "MAINT_SUP", "MAINT_TECH",
                "CLIENT_ADMIN", "CLIENT_SIGNER"}) {
            assertTrue(s.contains("'" + r + "'"), "缺角色范围种子 " + r);
        }
    }

    @Test
    void v35HasNoDestructiveStatements() throws Exception {
        String s = sql("V35__rbac_v2_schema.sql").toUpperCase();
        assertFalse(s.contains("TRUNCATE"), "严禁自动 TRUNCATE");
        assertFalse(s.contains("DELETE"), "严禁自动 DELETE");
        assertFalse(s.contains("DROP TABLE"), "schema 迁移不得删表");
    }

    @Test
    void v36BackfillsUserRolesAndOrgWithoutNewAccounts() throws Exception {
        String s = sql("V36__rbac_v2_backfill.sql");
        assertTrue(s.contains("INSERT IGNORE INTO sys_user_role"), "缺 sys_user_role 回填");
        assertTrue(s.contains("INSERT IGNORE INTO sys_org"), "缺 sys_org 节点");
        assertTrue(s.contains("MAINT_TECH"), "ASSISTANT/WORKER 应合并映射 MAINT_TECH");
        assertTrue(s.contains("CLIENT_ADMIN"), "use_unit 账号应回填 CLIENT_ADMIN");
        // 旧→新映射方向（spec §6.1 / M0 表1）
        assertTrue(s.contains("INSTALL_MANAGER") && s.contains("INST_PM"), "INSTALL_MANAGER→INST_PM");
        assertTrue(s.contains("LEADER") && s.contains("MAINT_SUP"), "LEADER→MAINT_SUP");
        String up = s.toUpperCase();
        assertFalse(up.contains("TRUNCATE"), "严禁自动 TRUNCATE");
        assertFalse(up.contains("DELETE"), "严禁自动 DELETE");
        // 不得新建账号（张工归 DemoDataSeeder）：回填只从 sys_employee/use_unit 派生
        assertFalse(s.contains("123456"), "迁移不得写入弱口令账号");
        assertFalse(s.contains("INST_MGR"), "V36 不得新建 INST_MGR 账号（张工归 Seeder）");
    }
}