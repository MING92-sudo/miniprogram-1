package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 平台写端点权限点声明核对（反射，防回归丢注解）。 */
class PlatformControllerPermTest {

    @Test
    void platformWriteEndpointsRequireReportRetryPerm() throws Exception {
        RequirePerm reupload = PlatformController.class
                .getMethod("reupload", String.class).getAnnotation(RequirePerm.class);
        RequirePerm reconcile = PlatformController.class
                .getMethod("reconcile", String.class, Map.class, String.class, HttpServletRequest.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(reupload, "reupload 缺少 @RequirePerm");
        assertNotNull(reconcile, "reconcile 缺少 @RequirePerm");
        assertEquals(PermissionMatrix.REPORT_RETRY_NEW, reupload.value());
        assertEquals(PermissionMatrix.REPORT_RETRY_NEW, reconcile.value());
    }

    @Test
    void queryElevatorRequiresElevatorEditPerm() throws Exception {
        RequirePerm perm = PlatformController.class
                .getMethod("queryElevator", Map.class).getAnnotation(RequirePerm.class);
        assertNotNull(perm, "queryElevator 缺少 @RequirePerm");
        assertEquals(PermissionMatrix.ELEVATOR_EDIT, perm.value());
    }
}
