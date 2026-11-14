package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 翻译器：24→71 映射、console:read adminConsole 门控、order/fault 反转、复现 RbacMatrixTest 冻结授权。 */
class LegacyCapabilityTranslatorTest {

    @Test
    void orderFaultSemanticReversal() {
        // 旧 order:*（维保工单）→ 新 plan/task/checklist/record，绝不映射到新 order:*
        Set<String> orderRead = LegacyCapabilityTranslator.translate("order:read");
        assertTrue(orderRead.contains("plan:view"));
        assertTrue(orderRead.contains("record:view"));
        assertFalse(orderRead.contains("order:view_pool"), "旧 order:read 不得映射新 order 域");
        // 旧 fault:*（急修单）→ 新 order:*
        Set<String> faultRead = LegacyCapabilityTranslator.translate("fault:read");
        assertTrue(faultRead.contains("order:view_pool"));
        assertTrue(faultRead.contains("order:view_own"));
        assertFalse(faultRead.contains("plan:view"));
    }

    @Test
    void fourCriticalCodesMapping() {
        assertTrue(LegacyCapabilityTranslator.translate("console:write").contains("config:manage"));
        assertTrue(LegacyCapabilityTranslator.translate("plan:write").contains("plan:generate"));
        assertTrue(LegacyCapabilityTranslator.translate("region:write").contains("region:write"));
        // console:read 无独立新码（被各域 view 吸收），translate 返回空集，判定走门控
        assertTrue(LegacyCapabilityTranslator.translate("console:read").isEmpty());
    }

    @Test
    void consoleReadGateMatchesFrozenGrant() {
        // 冻结 CONSOLE_READ = {ADMIN,SA,INSTALL_MANAGER,INSTALL_CLERK,MAINT_MANAGER,MAINT_BIZ,LEADER,MAINT_QUALITY}
        Set<String> frozen = PermissionMatrix.holders("console:read");
        for (String role : RoleCatalog.codes()) {
            assertEquals(frozen.contains(role), LegacyCapabilityTranslator.allows(role, "console:read"),
                    "console:read 门控不符: " + role);
        }
        // worker/门户 登录管理端即拒
        assertFalse(LegacyCapabilityTranslator.allows("WORKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("INSTALL_WORKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("UNIT_ADMIN", "console:read"));
    }

    @Test
    void reproducesFrozenGrantsForAll24x12() {
        // RbacMatrixTest 是唯一 oracle：翻译器 allows 必须与冻结 GRANTS 逐格相同
        for (String cap : PermissionMatrix.capabilities()) {
            Set<String> frozen = PermissionMatrix.holders(cap);
            for (String role : RoleCatalog.codes()) {
                assertEquals(frozen.contains(role), LegacyCapabilityTranslator.allows(role, cap),
                        cap + " × " + role);
            }
        }
    }

    @Test
    void unknownOldCodeFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> LegacyCapabilityTranslator.translate("order:write2"));
        assertFalse(LegacyCapabilityTranslator.allows("HACKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("ADMIN", null));
    }
}