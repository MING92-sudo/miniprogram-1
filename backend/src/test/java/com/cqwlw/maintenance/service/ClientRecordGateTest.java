package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户记录签字/拒签门禁 + 字段白名单（P1 T4，docs/14 §4.6 规则 1/2/3 + §4.7）：
 * 签字前依次 principalType=CUSTOMER → token useUnitId → 记录归属 → lifecycleStatus=PENDING_CLIENT_SIGNATURE
 * → confirmStatus=PENDING → 最新版本，任一失败 403/1403/422 无副作用；
 * 落库条件更新保证并发先签为准；客户路径绝不泄漏 reportPayload/context/shareToken 等内部字段；
 * 拒签原因必填且仅待签状态可拒（不触发平台 2.6——服务不注入上报结构）。
 */
class ClientRecordGateTest {

    private static final Set<String> CLIENT = Set.of("CLIENT_ADMIN");
    private static final String UNIT = "u1";
    private static final String RECORD_ID = "ur_c1";

    private MaintainRecordMapper records;
    private ElevatorMapper elevators;
    private FileStorageService files;
    private RecordLifecycleService lifecycle;
    private UnitRecordService service;

    /** LambdaUpdateWrapper 列名解析需 TableInfo 缓存（纯单测无 MyBatis 运行时；同 HandoverArchiveTest/DispatchServiceTest 做法） */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
    }

    @BeforeEach
    void setUp() {
        records = mock(MaintainRecordMapper.class);
        elevators = mock(ElevatorMapper.class);
        files = mock(FileStorageService.class);
        lifecycle = mock(RecordLifecycleService.class);
        WorkOrderService orders = mock(WorkOrderService.class);
        when(orders.paginate(any(), any())).thenAnswer(inv -> {
            List<?> list = inv.getArgument(0);
            return Map.of("list", list, "total", list.size());
        });
        service = new UnitRecordService(records, orders, files, elevators);
        service.setRecordLifecycleService(lifecycle);
        Elevator el = new Elevator();
        el.elevatorCode = "DT-1";
        el.useUnitId = UNIT;
        when(elevators.selectOne(any())).thenReturn(el);
    }

    /** 可签记录：PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING + 最新版本，且塞满内部字段供白名单断言 */
    private MaintainRecord signable() {
        MaintainRecord r = record(RECORD_ID, "PENDING_CLIENT_SIGNATURE");
        when(records.selectById(RECORD_ID)).thenReturn(r);
        return r;
    }

    private MaintainRecord record(String id, String lifecycleStatus) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorCode = "DT-1";
        r.elevatorName = "1号楼电梯";
        r.workType = "半月保";
        r.workTypeCode = "FM";
        r.workerName = "张伟";
        r.assistantName = "李强";
        r.duration = "00:35:00";
        r.todoDesc = "无";
        r.version = 1;
        r.lifecycleStatus = lifecycleStatus;
        r.confirmStatus = "PENDING";
        r.satisfaction = 0;
        // 内部字段：客户路径一律不得外泄（docs/14 §4.7「明确不返回」）
        r.shareToken = "sg-secret";
        r.reportPayloadJson = "{\"originalRecordId\":\"OR-1\"}";
        r.previewContextJson = "{\"address\":\"某小区\"}";
        r.originalRecordId = "OR-1";
        r.workerPlatformId = "P-W";
        r.assistantPlatformId = "P-A";
        r.reportStatus = "REPORTED";
        r.retryCount = 0;
        r.refuseReason = "内部复核退回原因";
        return r;
    }

    /** 非待签状态（双签未齐 PENDING_CLIENT_SIGN）签字 → 422，状态/签字字段不变，无任何写副作用 */
    @Test
    void signRejectedWhenNotPendingClientSignature() {
        MaintainRecord r = signable();
        r.lifecycleStatus = "PENDING_CLIENT_SIGN";

        BizException ex = assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/s.png"), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING", r.confirmStatus);
        assertNull(r.signatureUrl);
        verify(records, never()).update(any(), any());
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(lifecycle, never()).onClientSigned(any());

        // 详情同门禁：非待签状态客户不可见（docs/14 §4.6 表）
        assertEquals(422, assertThrows(BizException.class,
                () -> service.getAs(RECORD_ID, CLIENT, UNIT)).getCode());
    }

    /** 旧版本签字 → 422（存在 prevVersionId=v1.id 的 v2，客户只能签记录链末端） */
    @Test
    void signRejectedForSupersededVersion() {
        MaintainRecord v1 = signable();
        when(records.selectCount(any())).thenReturn(1L); // 存在后续版本

        BizException ex = assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/s.png"), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING", v1.confirmStatus);
        verify(records, never()).update(any(), any());
        verify(lifecycle, never()).onClientSigned(any());
    }

    /** 并发先签为准：条件更新受影响行 0 → 422 已签，内存状态不改写、不归档 */
    @Test
    void concurrentSecondSignGets422AndNoSideEffect() {
        MaintainRecord r = signable();
        when(records.update(any(), any())).thenReturn(0); // 被并发另一账号抢先落库

        BizException ex = assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/late.png"), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        assertEquals("PENDING", r.confirmStatus);
        assertNull(r.signatureUrl);
        verify(lifecycle, never()).onClientSigned(any());
    }

    /** 首签成功：条件更新命中 → CONFIRMED + onClientSigned（SIGNED→归档）；二次确认 → 422 不覆盖首签 */
    @Test
    void firstSignWinsAndSecondIsRejected() {
        MaintainRecord r = signable();
        when(records.update(any(), any())).thenReturn(1);

        assertEquals(Boolean.TRUE, service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/s1.png", "satisfaction", 5), CLIENT, UNIT).get("ok"));
        assertEquals("CONFIRMED", r.confirmStatus);
        assertEquals("https://x/s1.png", r.signatureUrl);
        assertEquals(5, r.satisfaction.intValue());
        verify(lifecycle, times(1)).onClientSigned(r);

        // 第二次确认（其他账号）→ 门禁 confirmStatus=PENDING 拒绝，首签签字不被覆盖
        BizException ex = assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/forged.png"), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        assertEquals("https://x/s1.png", r.signatureUrl);
        verify(records, times(1)).update(any(), any());
    }

    /** 无签名确认 → 422（先于任何写操作） */
    @Test
    void signWithoutSignatureRejected() {
        signable();
        BizException ex = assertThrows(BizException.class,
                () -> service.confirmAs(RECORD_ID, Map.of("satisfaction", 5), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        verify(records, never()).update(any(), any());
    }

    /** 字段白名单（docs/14 §4.7）：客户详情绝不返回内部字段，白名单字段齐备 */
    @Test
    void clientDetailOmitsInternalFields() {
        signable();
        Map<String, Object> m = service.getAs(RECORD_ID, CLIENT, UNIT);
        for (String k : new String[]{"reportPayload", "context", "previewContext", "shareToken",
                "originalRecordId", "workerPlatformId", "assistantPlatformId", "refuseReason",
                "reportStatus", "uploadStatus", "satisfaction", "retryCount", "signatureFileId",
                "nextMaintenanceDate", "workOrderId"}) {
            assertFalse(m.containsKey(k), k);
        }
        for (String k : new String[]{"id", "version", "prevVersionId", "lifecycleStatus", "elevatorCode",
                "elevatorName", "workType", "workTypeCode", "workerName", "assistantName", "checkinTime",
                "checkoutTime", "duration", "items", "photos", "problemCodes", "todoDesc",
                "workerSignatureUrl", "assistantSignatureUrl", "confirmStatus", "signatureUrl", "pdfUrl"}) {
            assertTrue(m.containsKey(k), k);
        }
    }

    /** items 白名单投影：仅 名称/结果/实测值/照片URL 四键（照片按 fileId 重签） */
    @Test
    void clientItemsProjectedToWhitelist() {
        MaintainRecord r = signable();
        r.itemsJson = JsonUtil.write(List.of(Map.of(
                "id", "it_1", "name", "曳引机", "result", "NORMAL", "value", 1.2,
                "problemCode", "", "photoFileIds", List.of("f1"), "notInThisRun", false)));
        when(files.urlOf("f1")).thenReturn("https://api/files/f1?s=x");

        Map<String, Object> m = service.getAs(RECORD_ID, CLIENT, UNIT);
        List<?> items = (List<?>) m.get("items");
        Map<?, ?> it = (Map<?, ?>) items.get(0);
        assertEquals(Set.of("name", "result", "value", "photos"), it.keySet());
        assertEquals("曳引机", it.get("name"));
        assertEquals(List.of("https://api/files/f1?s=x"), it.get("photos"));
        assertEquals(List.of("https://api/files/f1?s=x"), m.get("photos"));
    }

    /** pending 列表：仅 本单位 + 待签(PENDING_CLIENT_SIGNATURE) + 链末端最新版本 */
    @Test
    void pendingOnlySignableLatestOwnUnit() {
        MaintainRecord ok = signable();
        MaintainRecord notYet = record("ur_c2", "PENDING_CLIENT_SIGN"); // 双签未齐，客户不应提前看到
        MaintainRecord done = record("ur_c3", "ARCHIVED");              // 已签已归档
        MaintainRecord superseded = record("ur_c4", "PENDING_CLIENT_SIGNATURE"); // 旧版本
        when(records.selectList(any())).thenReturn(List.of(ok, notYet, done, superseded));
        when(records.selectCount(any())).thenReturn(0L, 1L); // ok=链末端；superseded=存在后续版本

        Map<String, Object> out = service.pendingAs(Map.of(), CLIENT, UNIT);
        List<?> list = (List<?>) out.get("list");
        assertEquals(1, list.size());
        assertEquals(RECORD_ID, ((Map<?, ?>) list.get(0)).get("id"));
    }

    /** 拒签：原因必填 + 仅待签状态可拒 + 成功落 REFUSED_RETURNED（不注入上报服务，结构保证不触发 2.6） */
    @Test
    void refuseRequiresReasonAndSignableState() {
        RecordLifecycleService real = new RecordLifecycleService(records, mock(WorkOrderMapper.class),
                mock(EmployeeMapper.class), mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                mock(RecordPdfService.class), files, mock(SysConfigMapper.class));
        MaintainRecord r = signable();
        r.shareToken = "sg-token";

        // 原因空 → 422，状态不变
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "  ")).getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);

        // 非待签状态（已签）→ 422
        r.lifecycleStatus = "SIGNED";
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰")).getCode());

        // 待签 + 原因齐备 → REFUSED_RETURNED + 拒签原因留痕
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰");
        assertEquals("REFUSED_RETURNED", r.lifecycleStatus);
        assertEquals("照片不清晰", r.refuseReason);
    }
}
