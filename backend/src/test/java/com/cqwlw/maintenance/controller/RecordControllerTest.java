package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.UnitRecordService;
import com.cqwlw.maintenance.service.WorkOrderService;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实 EmployeeScopeService + UnitRecordService，仅 mock mapper； */
class RecordControllerTest {

    private MaintainRecordMapper records;
    private EmployeeMapper employees;
    private FileStorageService files;
    private RecordController controller;

    @BeforeEach
    void setUp() {
        records = mock(MaintainRecordMapper.class);
        employees = mock(EmployeeMapper.class);
        files = mock(FileStorageService.class);
        UnitRecordService recordService = new UnitRecordService(records, mock(WorkOrderService.class),
                files, mock(ElevatorMapper.class));
        controller = new RecordController(recordService, new EmployeeScopeService(employees));
    }

    /** 请求桩：ATTR_ROLES=规范码集（AuthInterceptor.resolveRoles 口径），ATTR_EMP_ID=员工 id */
    private MockHttpServletRequest req(Set<String> roles, String empId) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/records/ur_1");
        r.setAttribute(AuthInterceptor.ATTR_ROLES, roles);
        if (empId != null) {
            r.setAttribute(AuthInterceptor.ATTR_EMP_ID, empId);
        }
        return r;
    }

    private Employee emp(String id, String role, String platformId, String groupName) {
        Employee e = new Employee();
        e.id = id;
        e.role = role;
        e.platformId = platformId;
        e.groupName = groupName;
        return e;
    }

    /** 记录桩：塞满 record-detail 页消费的内部字段（生命周期/版本链/shareToken/context/items 明细） */
    private MaintainRecord record(String id, String workerPid, String assistantPid) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = "DT-1";
        r.elevatorName = "1号楼电梯";
        r.workType = "半月保";
        r.workTypeCode = "FM";
        r.workerPlatformId = workerPid;
        r.assistantPlatformId = assistantPid;
        r.duration = "00:35:00";
        r.confirmStatus = "PENDING";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.version = 2;
        r.prevVersionId = "ur_0";
        r.shareToken = "sg-1";
        r.archiveHash = "9f3c7a21b8d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e";
        r.itemsJson = JsonUtil.write(List.of(Map.of(
                "id", "it_1", "name", "曳引机", "result", "NORMAL", "value", 1.2,
                "photoFileIds", List.of("f1"))));
        r.previewContextJson = "{\"address\":\"某小区\",\"itemTotal\":76}";
        return r;
    }

    /** MAINT_TECH（主角色规范码，同生产李振国）读本人记录 → 200，内部全量投影含生命周期/版本链/shareToken/items 明细 */
    @Test
    void techReadsOwnRecordWithFullInternalProjection() {
        when(employees.selectById("emp_tech")).thenReturn(emp("emp_tech", "MAINT_TECH", "P_SELF", "g1"));
        when(records.selectById("ur_1")).thenReturn(record("ur_1", "P_SELF", "P_A"));
        when(files.urlOf("f1")).thenReturn("https://api/files/f1?s=x");

        Map<String, Object> m = controller.get("ur_1", req(Set.of("MAINT_TECH"), "emp_tech")).getData();

        assertEquals("ur_1", m.get("id"));
        assertEquals("PENDING_CLIENT_SIGNATURE", m.get("lifecycleStatus"));
        assertEquals(2, m.get("version"));
        assertEquals("ur_0", m.get("prevVersionId"));
        assertEquals("9f3c7a21", m.get("hashPrefix"));
        assertEquals("sg-1", m.get("shareToken")); // record-detail copySignLink 依赖（内部投影）
        List<?> items = (List<?>) m.get("items");
        assertEquals("曳引机", ((Map<?, ?>) items.get(0)).get("name")); // items 明细不裁剪
        assertEquals(List.of("https://api/files/f1?s=x"), ((Map<?, ?>) items.get(0)).get("photos"));
        assertEquals("某小区", ((Map<?, ?>) m.get("context")).get("address"));
    }

    /** MAINT_TECH（主角色旧码 WORKER，同生产张伟）读他班组记录 → 1403，无副作用；旧码/规范码谓词行为一致 */
    @Test
    void techReadsOtherCrewRecordGets1403() {
        when(employees.selectById("emp_w")).thenReturn(emp("emp_w", "WORKER", "P_SELF", "g1"));
        when(records.selectById("ur_2")).thenReturn(record("ur_2", "P_OTHER", "P_OTHER2"));

        BizException ex = assertThrows(BizException.class,
                () -> controller.get("ur_2", req(Set.of("MAINT_TECH"), "emp_w")));
        assertEquals(1403, ex.getCode());
        assertTrue(ex.getMessage().contains("本人或本班组"));
        verify(records, never()).updateById(any(MaintainRecord.class));
    }

    /** MAINT_SUP（主角色旧码 LEADER）读本组组员记录 → 200（主维保或配合人员命中其一即可） */
    @Test
    void supReadsGroupMemberRecordGets200() {
        Employee leader = emp("emp_l", "LEADER", "P_L", "g1");
        when(employees.selectById("emp_l")).thenReturn(leader);
        when(employees.selectList(any())).thenReturn(List.of(leader, emp("emp_m", "WORKER", "P_M", "g1")));
        when(records.selectById("ur_3")).thenReturn(record("ur_3", "P_M", ""));
        when(records.selectById("ur_4")).thenReturn(record("ur_4", "P_X", "P_M"));

        assertEquals("ur_3", controller.get("ur_3", req(Set.of("MAINT_SUP"), "emp_l")).getData().get("id"));
        assertEquals("ur_4", controller.get("ur_4", req(Set.of("MAINT_SUP"), "emp_l")).getData().get("id"));
    }

    /** MAINT_MGR（旧码 MAINT_MANAGER 部门级）/PLATFORM_ADMIN（旧码 ADMIN 平台级）→ null 不限，全量 200 */
    @Test
    void managerRolesReadAnyRecord() {
        when(employees.selectById("emp_mm")).thenReturn(emp("emp_mm", "MAINT_MANAGER", "P_MM", ""));
        when(employees.selectById("emp_adm")).thenReturn(emp("emp_adm", "ADMIN", "", ""));
        when(records.selectById("ur_5")).thenReturn(record("ur_5", "P_X", "P_Y"));

        assertEquals("ur_5", controller.get("ur_5", req(Set.of("MAINT_MGR"), "emp_mm")).getData().get("id"));
        assertEquals("ur_5", controller.get("ur_5", req(Set.of("PLATFORM_ADMIN"), "emp_adm")).getData().get("id"));
    }

    @Test
    void bizPrimaryRoleEmptyScopeGets1403EvenForOwnPlatformId() {
        when(employees.selectById("emp_b")).thenReturn(emp("emp_b", "MAINT_BIZ", "P_B", ""));
        when(records.selectById("ur_1")).thenReturn(record("ur_1", "P_B", null)); // assistant NULL 存量行
        when(records.selectById("ur_2")).thenReturn(record("ur_2", null, null));  // 双 NULL 存量行

        BizException ex = assertThrows(BizException.class,
                () -> controller.get("ur_1", req(Set.of("MAINT_TECH", "MAINT_BIZ"), "emp_b")));
        assertEquals(1403, ex.getCode());
        assertEquals(1403, assertThrows(BizException.class,
                () -> controller.get("ur_2", req(Set.of("MAINT_TECH", "MAINT_BIZ"), "emp_b"))).getCode());
    }

    /** 无 record:view 的角色（INSTALLER/MAINT_BIZ 单角色/客户令牌）→ 403，门禁先于任何查询（无副作用） */
    @Test
    void rolesWithoutRecordViewDenied403() {
        for (Set<String> roles : List.of(Set.of("INSTALLER"), Set.of("MAINT_BIZ"), Set.of("CLIENT_ADMIN"))) {
            BizException ex = assertThrows(BizException.class,
                    () -> controller.get("ur_1", req(roles, "emp_x")), String.valueOf(roles));
            assertEquals(403, ex.getCode(), String.valueOf(roles));
            assertTrue(ex.getMessage().contains("record:view"), String.valueOf(roles));
        }
        verify(employees, never()).selectById(any());
        verify(records, never()).selectById(any());
    }

    /** 不存在的记录 id → 1404（内部端点非匿名，1404 正常；管理角色不限范围） */
    @Test
    void missingRecordGets1404() {
        when(employees.selectById("emp_mm")).thenReturn(emp("emp_mm", "MAINT_MANAGER", "", ""));
        when(records.selectById("nope")).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> controller.get("nope", req(Set.of("MAINT_MGR"), "emp_mm")));
        assertEquals(1404, ex.getCode());
    }

    /** 会话失效（empId 无对应员工）→ 401（EmployeeScopeService.require 口径） */
    @Test
    void unknownEmployeeGets401() {
        when(employees.selectById(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> controller.get("ur_1", req(Set.of("MAINT_TECH"), null)));
        assertEquals(401, ex.getCode());
    }
}
