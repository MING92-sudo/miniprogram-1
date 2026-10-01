package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.auth.CurrentUser;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2.6 报文中的 recorder / recorderPhone 属**维保人员1**（平台标注必填）。
 * 原实现查不到该员工时会退化为"随便取一个 WORKER 的号码"——把不相干者的号码
 * 写进合规上报，而 2.6 记录的是实际作业人，号码对不上即为记录失真，故必须 fail-closed。
 */
class ReportPayloadRecorderTest {

    private EmployeeMapper employeeMapper;
    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        employeeMapper = mock(EmployeeMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        when(companyMapper.selectList(null)).thenReturn(List.of(new Company()));
        service = new WorkOrderService(
                mock(WorkOrderMapper.class),
                mock(ElevatorMapper.class),
                mock(UseUnitMapper.class),
                employeeMapper,
                companyMapper,
                mock(MaintainRecordMapper.class),
                mock(FaultMapper.class),
                mock(InspectRecordMapper.class),
                mock(ChecklistService.class),
                mock(ApprovalService.class),
                mock(EvidenceTokenService.class),
                mock(FileStorageService.class),
                mock(PlatformReportService.class),
                new AppProperties(),
                mock(CurrentUser.class));
    }

    private MaintainRecord record(String workerName, String workerPlatformId) {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.workerName = workerName;
        r.workerPlatformId = workerPlatformId;
        r.elevatorCode = "212520";
        r.workTypeCode = "HM";
        return r;
    }

    private Employee employee(String name, String platformId, String phone) {
        Employee e = new Employee();
        e.id = "e_" + name;
        e.name = name;
        e.platformId = platformId;
        e.phone = phone;
        return e;
    }

    @Test
    void recorderAndPhoneComeFromPrincipal() {
        when(employeeMapper.selectOne(any())).thenReturn(employee("张伟", "P_PRINCIPAL", "13911111111"));

        Map<String, Object> p = service.buildReportPayload(record("张伟", "P_PRINCIPAL"), null, null);

        assertEquals("张伟", p.get("recorder"));
        assertEquals("13911111111", p.get("recorderPhone"));
    }

    @Test
    void rejectsWhenPlatformIdAbsentInsteadOfMatchingByName() {
        // 严禁只按姓名匹配（姓名会重名）：工单无 platform_id 时直接 422，
        // 不得退化到姓名匹配把某个号码写进 2.6 合规上报
        when(employeeMapper.selectOne(any())).thenReturn(employee("张伟", null, "13911111111"));

        assertEquals(422, assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张伟", ""), null, null)).getCode());
    }

    @Test
    void rejectsWhenPrincipalNameDiffersFromRecord() {
        // platform_id 查得到人，但姓名与工单记录的维保人员1对不上 → 拒绝
        when(employeeMapper.selectOne(any())).thenReturn(employee("李强", "P_PRINCIPAL", "13911111111"));

        assertEquals(422, assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张伟", "P_PRINCIPAL"), null, null)).getCode());
    }

    @Test
    void failsClosedWhenPrincipalNotFound() {
        when(employeeMapper.selectOne(any())).thenReturn(null);

        assertEquals(422, assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张伟", "P_PRINCIPAL"), null, null)).getCode());
    }

    @Test
    void failsClosedWhenPrincipalPhoneBlank() {
        when(employeeMapper.selectOne(any())).thenReturn(employee("张伟", "P_PRINCIPAL", ""));

        assertEquals(422, assertThrows(BizException.class,
                () -> service.buildReportPayload(record("张伟", "P_PRINCIPAL"), null, null)).getCode());
    }
}