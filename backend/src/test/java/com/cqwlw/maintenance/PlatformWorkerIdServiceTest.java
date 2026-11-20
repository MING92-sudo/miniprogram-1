package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformTokenService;
import com.cqwlw.maintenance.service.PlatformWorkerIdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 维保人员平台ID回填（docs/04 B.5）：
 * 2.4 建档只返回 code/message，platform_id 必须靠 2.5 按证书号精确匹配回填。
 * 证书号重复不得自动取首条，否则会把别人的平台ID写到本系统人员上，2.6 上报错误数据。
 */
class PlatformWorkerIdServiceTest {

    private PlatformClient platformClient;
    private PlatformTokenService tokenService;
    private EmployeeMapper employeeMapper;
    private PlatformWorkerIdService service;

    @BeforeEach
    void setUp() {
        platformClient = mock(PlatformClient.class);
        tokenService = mock(PlatformTokenService.class);
        employeeMapper = mock(EmployeeMapper.class);
        service = new PlatformWorkerIdService(platformClient, tokenService, employeeMapper);
        when(tokenService.configured()).thenReturn(true);
    }

    private Employee employee(String cert) {
        Employee e = new Employee();
        e.id = "emp_" + cert;
        e.certificate = cert;
        e.name = "维保员";
        return e;
    }

    private Map<String, Object> worker(String cert, Object id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workManCertificate", cert);
        m.put("id", id);
        return m;
    }

    @Test
    void syncedByCertificate() {
        Employee e = employee("CERT-1");
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(
                worker("OTHER", "999"), worker("CERT-1", 123456L)));
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));

        assertEquals(PlatformWorkerIdService.SYNCED, service.backfillByCertificate("CERT-1"));
        assertEquals("123456", e.platformId);
        assertEquals("SYNCED", e.syncStatus);
        verify(employeeMapper).updateById(e);
    }

    @Test
    void duplicateCertificateDoesNotAutoPickFirst() {
        // docs/04 B.5：证书号重复必须告警人工处理，不自动取第一条
        Employee e = employee("CERT-DUP");
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(
                worker("CERT-DUP", 111L), worker("CERT-DUP", 222L)));
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));

        assertEquals(PlatformWorkerIdService.AMBIGUOUS, service.backfillByCertificate("CERT-DUP"));
        assertNull(e.platformId);
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    @Test
    void notFoundDoesNotWrite() {
        Employee e = employee("CERT-2");
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(worker("OTHER", 9L)));
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));

        assertEquals(PlatformWorkerIdService.NOT_FOUND, service.backfillByCertificate("CERT-2"));
        assertNull(e.platformId);
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    @Test
    void blankCertificateSkipsPlatformCall() {
        assertEquals(PlatformWorkerIdService.NO_CERT, service.backfillByCertificate("  "));
        verify(platformClient, never()).queryWorkList(anyString(), any());
    }

    @Test
    void unconfiguredPlatformSkipsWithoutException() {
        when(tokenService.configured()).thenReturn(false);
        assertEquals(PlatformWorkerIdService.DISABLED, service.backfillByCertificate("CERT-3"));
        verify(platformClient, never()).queryWorkList(anyString(), any());
    }

    @Test
    void backfillAllMissingCountsResults() {
        Employee e1 = employee("CERT-A");
        Employee e2 = employee("CERT-B");
        // 第1次 selectList=backfillAllMissing 扫全量；第2次=backfillByCertificate 按证书号命中 e1
        when(employeeMapper.selectList(any())).thenReturn(List.of(e1, e2), List.of(e1));
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(
                worker("CERT-A", "P-A")));

        Map<String, Object> summary = service.backfillAllMissing();

        assertEquals(2, summary.get("scanned"));
        assertEquals(1, summary.get("synced"));
        assertEquals(1, summary.get("notFound"));
        assertEquals("P-A", e1.platformId);
        assertNull(e2.platformId);
    }

    // ---- 建档后全量回填（一次 2.5 查询补齐全单位所有本地人员）----

    @Test
    void backfillAllQueriesOnceAndFillsEveryone() {
        // 2.5 是全量查询：一次调用返回整个单位所有绑定人员，按证书号回填所有本地有证书的人
        Employee e1 = employee("CERT-1");
        Employee e2 = employee("CERT-2");
        when(employeeMapper.selectList(any())).thenReturn(List.of(e1, e2));
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(
                worker("CERT-2", "P-2"), worker("CERT-1", "P-1"), worker("OTHER", "P-9")));

        Map<String, Object> r = service.backfillAllFromPlatform();

        // 只查一次 2.5，即便要回填多人
        verify(platformClient, org.mockito.Mockito.times(1)).queryWorkList(anyString(), any());
        assertEquals(3, r.get("platformWorkers"));
        assertEquals(2, r.get("scanned"));
        assertEquals(2, r.get("synced"));
        assertEquals(0, r.get("notFound"));
        assertEquals("P-1", e1.platformId);
        assertEquals("P-2", e2.platformId);
        assertEquals(PlatformWorkerIdService.SYNCED, r.get("status"));
    }

    @Test
    void backfillAllSkipsDuplicateCertificateAndStillFillsOthers() {
        Employee eDup = employee("CERT-DUP");
        Employee eOk = employee("CERT-OK");
        when(employeeMapper.selectList(any())).thenReturn(List.of(eDup, eOk));
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(
                worker("CERT-DUP", 111L), worker("CERT-DUP", 222L), worker("CERT-OK", "P-OK")));

        Map<String, Object> r = service.backfillAllFromPlatform();

        assertNull(eDup.platformId, "证书号重复不得自动取首条");
        assertEquals("P-OK", eOk.platformId, "其他人员仍应正常回填");
        assertEquals(1, r.get("synced"));
        assertEquals(1, r.get("ambiguous"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ((List<?>) r.get("conflictCertificates")).contains("CERT-DUP"));
    }

    @Test
    void backfillAllUpdatesExistingPlatformId() {
        // 全量回填以平台为准：本地已有 platform_id 也更新为 2.5 最新值
        Employee e = employee("CERT-UP");
        e.platformId = "OLD-ID";
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(worker("CERT-UP", "NEW-ID")));

        service.backfillAllFromPlatform();

        assertEquals("NEW-ID", e.platformId);
        verify(employeeMapper).updateById(e);
    }

    @Test
    void backfillAllUnconfiguredSkipsPlatformCall() {
        when(tokenService.configured()).thenReturn(false);
        Map<String, Object> r = service.backfillAllFromPlatform();

        verify(platformClient, never()).queryWorkList(anyString(), any());
        assertEquals(1, r.get("disabled"));
        assertEquals(0, r.get("synced"));
    }

    @Test
    void backfillWithRetryReturnsSelfStatusForMatchedCertificate() {
        // 建档后入口：本次建档人命中 2.5 → status=SYNCED，不进入重试队列
        Employee e = employee("CERT-NEW");
        when(employeeMapper.selectList(any())).thenReturn(List.of(e));
        when(platformClient.queryWorkList(anyString(), any())).thenReturn(List.of(worker("CERT-NEW", "P-NEW")));

        Map<String, Object> r = service.backfillWithRetry("CERT-NEW");

        assertEquals(PlatformWorkerIdService.SYNCED, r.get("status"));
        assertEquals("P-NEW", e.platformId);
        verify(platformClient, org.mockito.Mockito.times(1)).queryWorkList(anyString(), any());
    }
}
