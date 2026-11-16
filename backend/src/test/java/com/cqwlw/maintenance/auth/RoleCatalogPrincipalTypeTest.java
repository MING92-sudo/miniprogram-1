package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.auth.RoleCatalog.PrincipalType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1 主体类型底座：EMPLOYEE/CUSTOMER 归类、客户部门投影为 null、客户不可分配（task-1-brief 断言逐字）。 */
class RoleCatalogPrincipalTypeTest {

    @Test
    void principalTypeOfClassifiesCustomerAndEmployee() {
        assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("CLIENT_ADMIN"));
        assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("CLIENT_SIGNER"));
        assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("UNIT_ADMIN")); // 别名归一
        assertEquals(PrincipalType.EMPLOYEE, RoleCatalog.principalTypeOf("MAINT_TECH"));
        assertEquals(PrincipalType.EMPLOYEE, RoleCatalog.principalTypeOf("ADMIN"));
        assertNull(RoleCatalog.principalTypeOf("HACKER"));
        assertNull(RoleCatalog.principalTypeOf(null));
    }

    @Test
    void customerDepartmentProjectsNull() {
        assertNull(RoleCatalog.departmentOf("CLIENT_ADMIN"));
        assertNull(RoleCatalog.departmentOf("UNIT_ADMIN")); // P1：客户不再属 UNIT 部门
        assertEquals("MAINT", RoleCatalog.departmentOf("WORKER")); // 内部不变
    }

    @Test
    void customerNotAssignableAndNoAdminConsole() {
        assertTrue(RoleCatalog.assignableRoles().stream().noneMatch(r -> r.code().startsWith("CLIENT")));
        assertFalse(RoleCatalog.canUseAdminConsole("CLIENT_ADMIN")); // 保持 false
    }
}
