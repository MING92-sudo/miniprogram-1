package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ElevatorControllerPermTest {
    @Test
    void dispatchNowRequiresOrderDispatch() throws Exception {
        RequirePerm perm = ElevatorController.class
                .getMethod("dispatchNow", String.class).getAnnotation(RequirePerm.class);
        assertNotNull(perm, "dispatchNow 缺少 @RequirePerm（手动派单应归 order:dispatch 而非 CONSOLE_WRITE 兜底）");
        assertEquals(PermissionMatrix.ORDER_DISPATCH, perm.value());
    }
}
