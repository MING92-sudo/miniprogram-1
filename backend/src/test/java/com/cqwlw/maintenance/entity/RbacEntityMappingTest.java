package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 五表实体 @TableName 映射 + Employee.scopeVersion + bumpScopeVersion 签名（无库，反射断言）。 */
class RbacEntityMappingTest {

    private String table(Class<?> c) {
        TableName a = c.getAnnotation(TableName.class);
        assertNotNull(a, c.getSimpleName() + " 缺 @TableName");
        return a.value();
    }

    @Test
    void entitiesMapToRbacTables() {
        assertEquals("sys_org", table(SysOrg.class));
        assertEquals("sys_user_role", table(SysUserRole.class));
        assertEquals("sys_role_scope", table(SysRoleScope.class));
        assertEquals("sys_grant", table(SysGrant.class));
        assertEquals("sys_audit_log", table(SysAuditLog.class));
    }

    @Test
    void employeeHasScopeVersion() throws Exception {
        Field f = Employee.class.getField("scopeVersion");
        assertEquals(Integer.class, f.getType());
    }

    @Test
    void employeeMapperHasBumpScopeVersion() throws Exception {
        assertNotNull(EmployeeMapper.class.getMethod("bumpScopeVersion", String.class));
    }
}