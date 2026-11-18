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
import static org.mockito.Mockito.atMost;
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
        // P2b T9：客户归属改看记录快照列（不再回查电梯当前归属）；既有门禁夹具补快照，
        // 门禁断言一律不动——只让夹具对齐新的快照口径。
        r.useUnitId = UNIT;
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

        // 查看门禁（§4.6 可见性表）：PENDING_CLIENT_SIGN 属不可见集合（双签未齐，客户不应提前看到）→ 422
        assertEquals(422, assertThrows(BizException.class,
                () -> service.getAs(RECORD_ID, CLIENT, UNIT)).getCode());
    }

    /** T4-C2（§4.6 可见性表）：SIGNED/ARCHIVED/REFUSED_RETURNED 客户只读可见；签字门禁仍拒（可见≠可签） */
    @Test
    void signedArchivedRefusedAreReadOnlyVisible() {
        for (String state : new String[]{"SIGNED", "ARCHIVED", "REFUSED_RETURNED"}) {
            MaintainRecord r = signable();
            r.lifecycleStatus = state;
            r.confirmStatus = "REFUSED_RETURNED".equals(state) ? "PENDING" : "CONFIRMED";
            if ("ARCHIVED".equals(state)) {
                r.archiveFileId = "f_pdf";
                when(files.urlOf("f_pdf")).thenReturn("https://api/files/f_pdf?s=x");
            }
            Map<String, Object> m = service.getAs(RECORD_ID, CLIENT, UNIT);
            assertEquals(state, m.get("lifecycleStatus"));
            assertFalse(m.containsKey("shareToken"), state);
            assertFalse(m.containsKey("reportPayload"), state);
            if ("ARCHIVED".equals(state)) {
                assertEquals("https://api/files/f_pdf?s=x", m.get("pdfUrl"));
            }
            // 只读：签字门禁仍拒（SIGNED/ARCHIVED 非可签状态；REFUSED_RETURNED 客户不能自行重签）
            assertEquals(422, assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                    Map.of("signatureUrl", "https://x/s.png"), CLIENT, UNIT)).getCode());
        }
        verify(records, never()).update(any(), any());
    }

    /** T4-C2：PENDING_REVIEW / NULL（历史行）不在可见集合 → 详情 422 */
    @Test
    void pendingReviewAndNullLifecycleInvisible() {
        MaintainRecord r = signable();
        r.lifecycleStatus = "PENDING_REVIEW";
        assertEquals(422, assertThrows(BizException.class,
                () -> service.getAs(RECORD_ID, CLIENT, UNIT)).getCode());
        r.lifecycleStatus = null;
        assertEquals(422, assertThrows(BizException.class,
                () -> service.getAs(RECORD_ID, CLIENT, UNIT)).getCode());
    }

    /** §4.6 规则 1：旧版本即使 SIGNED 也不可见（历史版本仅内部/审计） */
    @Test
    void supersededSignedRecordInvisible() {
        MaintainRecord r = signable();
        r.lifecycleStatus = "SIGNED";
        when(records.selectCount(any())).thenReturn(1L);
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
        // T4-I3（§4.7）：satisfaction 不接收不落库（评价关闭），入参被忽略
        assertEquals(0, r.satisfaction.intValue());
        verify(lifecycle, times(1)).onClientSigned(r);

        // 第二次确认（其他账号）→ 门禁 confirmStatus=PENDING 拒绝，首签签字不被覆盖
        BizException ex = assertThrows(BizException.class, () -> service.confirmAs(RECORD_ID,
                Map.of("signatureUrl", "https://x/forged.png"), CLIENT, UNIT));
        assertEquals(422, ex.getCode());
        assertEquals("https://x/s1.png", r.signatureUrl);
        verify(records, times(1)).update(any(), any());
    }

    /** T4-C1：匿名 token 签字同门禁——token 错 401 先于状态披露；非待签/NULL/旧版本 422 且零副作用 */
    @Test
    void confirmByTokenGatesStateAndVersion() {
        MaintainRecord r = signable();
        r.shareToken = "sg-token";

        assertEquals(401, assertThrows(BizException.class, () -> service.confirmByToken(RECORD_ID, "bad",
                Map.of("signatureUrl", "https://x/s.png"))).getCode());

        // 双签未齐（PENDING_CLIENT_SIGN）→ 422：持分享链接不得提前签字（虚假签字合规绕过）
        r.lifecycleStatus = "PENDING_CLIENT_SIGN";
        assertEquals(422, assertThrows(BizException.class, () -> service.confirmByToken(RECORD_ID, "sg-token",
                Map.of("signatureUrl", "https://x/s.png"))).getCode());

        // 历史 NULL lifecycle → 422
        r.lifecycleStatus = null;
        assertEquals(422, assertThrows(BizException.class, () -> service.confirmByToken(RECORD_ID, "sg-token",
                Map.of("signatureUrl", "https://x/s.png"))).getCode());

        // 旧版本（存在 prevVersionId=本记录 id 的后续版本）→ 422
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        when(records.selectCount(any())).thenReturn(1L);
        assertEquals(422, assertThrows(BizException.class, () -> service.confirmByToken(RECORD_ID, "sg-token",
                Map.of("signatureUrl", "https://x/s.png"))).getCode());

        assertEquals("PENDING", r.confirmStatus);
        assertNull(r.signatureUrl);
        verify(records, never()).update(any(), any());
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(lifecycle, never()).onClientSigned(any());
    }

    /** T4-C1：token 首签走同一条件更新（非 updateById 读-改-写）；satisfaction 字符串入参不再 CCE→500 */
    @Test
    void confirmByTokenSignsWithConditionalUpdate() {
        MaintainRecord r = signable();
        r.shareToken = "sg-token";
        when(records.update(any(), any())).thenReturn(1);

        assertEquals(Boolean.TRUE, service.confirmByToken(RECORD_ID, "sg-token",
                Map.of("signatureUrl", "https://x/t1.png", "satisfaction", "五")).get("ok"));
        assertEquals("CONFIRMED", r.confirmStatus);
        assertEquals("https://x/t1.png", r.signatureUrl);
        assertEquals(0, r.satisfaction.intValue()); // 不接收 satisfaction（§4.7）
        verify(lifecycle, times(1)).onClientSigned(r);
        verify(records, never()).updateById(any(MaintainRecord.class));

        // 并发后来者：内存态已 CONFIRMED → 门禁 422，首签不被覆盖，条件更新只发生一次
        BizException ex = assertThrows(BizException.class, () -> service.confirmByToken(RECORD_ID, "sg-token",
                Map.of("signatureUrl", "https://x/forged.png")));
        assertEquals(422, ex.getCode());
        assertEquals("https://x/t1.png", r.signatureUrl);
        verify(records, times(1)).update(any(), any());
    }

    /** C-25⑧：匿名 token 端点记录不存在 → 401 与错 token 同文案（消除 1404/401 存在性 oracle），且零写副作用 */
    @Test
    void anonymousTokenEndpointsUnknownRecordIs401() {
        when(records.selectById("ur_missing")).thenReturn(null);

        BizException view = assertThrows(BizException.class, () -> service.signView("ur_missing", "sg-token"));
        assertEquals(401, view.getCode());
        assertEquals("确认链接无效或已失效", view.getMessage());

        BizException sign = assertThrows(BizException.class, () -> service.confirmByToken("ur_missing", "sg-token",
                Map.of("signatureUrl", "https://x/s.png")));
        assertEquals(401, sign.getCode());
        assertEquals("确认链接无效或已失效", sign.getMessage());

        verify(records, never()).update(any(), any());
        verify(records, never()).updateById(any(MaintainRecord.class));
        verify(lifecycle, never()).onClientSigned(any());
    }

    /** C-25⑧ 对照：signView 错/缺 token → 401（与不存在记录同文案，匿名探测者两种情形不可区分） */
    @Test
    void signViewWrongToken401() {
        MaintainRecord r = signable();
        r.shareToken = "sg-token";
        BizException ex = assertThrows(BizException.class, () -> service.signView(RECORD_ID, "bad"));
        assertEquals(401, ex.getCode());
        assertEquals("确认链接无效或已失效", ex.getMessage());
        assertEquals(401, assertThrows(BizException.class, () -> service.signView(RECORD_ID, null)).getCode());
    }

    /** C-25⑧ 边界：客户认证路径（getAs/confirmAs）不归一，记录不存在仍 1404（仅匿名 token 端点收敛） */
    @Test
    void authenticatedClientPathsUnknownRecordStill404() {
        when(records.selectById("ur_missing")).thenReturn(null);
        assertEquals(1404, assertThrows(BizException.class,
                () -> service.getAs("ur_missing", CLIENT, UNIT)).getCode());
        assertEquals(1404, assertThrows(BizException.class, () -> service.confirmAs("ur_missing",
                Map.of("signatureUrl", "https://x/s.png"), CLIENT, UNIT)).getCode());
        verify(records, never()).update(any(), any());
        verify(records, never()).updateById(any(MaintainRecord.class));
    }

    /** T4-I3：sign-view 不再返回 satisfaction（评价关闭，签字链路不带满意度） */
    @Test
    void signViewOmitsSatisfaction() {
        MaintainRecord r = signable();
        r.shareToken = "sg-token";
        Map<String, Object> v = service.signView(RECORD_ID, "sg-token");
        assertFalse(v.containsKey("satisfaction"));
        assertEquals(Boolean.FALSE, v.get("confirmed"));
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
        MaintainRecord nextOfSuperseded = record("ur_c4_v2", "PENDING_CLIENT_SIGNATURE");
        nextOfSuperseded.prevVersionId = "ur_c4"; // supersededIds 批量 IN 查询命中的后续版本行
        when(records.selectList(any()))
                .thenReturn(List.of(ok, notYet, done, superseded))
                .thenReturn(List.of(nextOfSuperseded));
        Elevator el = new Elevator();
        el.elevatorCode = "DT-1";
        el.useUnitId = UNIT;
        when(elevators.selectList(any())).thenReturn(List.of(el)); // P2b T9 后 pendingAs 不再查电梯，此桩保留仅作兼容

        Map<String, Object> out = service.pendingAs(Map.of(), CLIENT, UNIT);
        List<?> list = (List<?>) out.get("list");
        assertEquals(1, list.size());
        assertEquals(RECORD_ID, ((Map<?, ?>) list.get(0)).get("id"));
        // P2b T9：归属改看记录快照列，电梯表 0 次查询；链末端仍批量判定，记录表至多 2 次
        // （候选 1 + supersededIds 1）；不再逐条 selectCount / selectOne
        verify(records, never()).selectCount(any());
        verify(records, atMost(2)).selectList(any());
        verify(elevators, never()).selectOne(any());
        verify(elevators, atMost(1)).selectList(any());
    }

    /** P2b T9 查询次数锁定：记录数/电梯数增长时 pendingAs 查询次数恒定（2+0），判定与旧逐条实现等价 */
    @Test
    void pendingAsQueryCountIsConstantAcrossRecords() {
        MaintainRecord a = record("ur_a", "PENDING_CLIENT_SIGNATURE");   // DT-1 本单位
        MaintainRecord b = record("ur_b", "PENDING_CLIENT_SIGNATURE");
        b.elevatorCode = "DT-2";                                          // DT-2 本单位
        MaintainRecord old = record("ur_c", "PENDING_CLIENT_SIGNATURE"); // 旧版本 → 被替代
        MaintainRecord foreign = record("ur_d", "PENDING_CLIENT_SIGNATURE");
        foreign.elevatorCode = "DT-9";                                    // 他单位 → 归属过滤
        foreign.useUnitId = "u-other";                                    // P2b T9：快照列钉住他单位
        MaintainRecord nextOfOld = record("ur_c_v2", "PENDING_CLIENT_SIGNATURE");
        nextOfOld.prevVersionId = "ur_c";
        when(records.selectList(any()))
                .thenReturn(List.of(a, b, old, foreign))
                .thenReturn(List.of(nextOfOld));
        when(elevators.selectList(any())).thenReturn(List.of(
                elevatorOf("DT-1", UNIT), elevatorOf("DT-2", UNIT), elevatorOf("DT-9", "u-other")));

        Map<String, Object> out = service.pendingAs(Map.of(), CLIENT, UNIT);
        List<?> list = (List<?>) out.get("list");
        assertEquals(List.of("ur_a", "ur_b"), list.stream().map(m -> ((Map<?, ?>) m).get("id")).toList());

        verify(records, times(2)).selectList(any());   // 候选 + supersededIds，恒定不随记录数增长
        verify(records, never()).selectCount(any());
        verify(elevators, never()).selectList(any()); // P2b T9：快照谓词不再查电梯归属（原 times(1) 为旧实现细节）
        verify(elevators, never()).selectOne(any());
    }

    private static Elevator elevatorOf(String code, String useUnitId) {
        Elevator el = new Elevator();
        el.elevatorCode = code;
        el.useUnitId = useUnitId;
        return el;
    }

    /** 拒签：原因必填 + 仅待签状态可拒 + 成功落 REFUSED_RETURNED（不注入上报服务，结构保证不触发 2.6） */
    @Test
    void refuseRequiresReasonAndSignableState() {
        RecordLifecycleService real = new RecordLifecycleService(records, mock(WorkOrderMapper.class),
                mock(EmployeeMapper.class), mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                mock(RecordPdfService.class), files, mock(SysConfigMapper.class));
        MaintainRecord r = signable();
        r.shareToken = "sg-token";

        // 原因空 → 422，状态不变、不落库
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "  ")).getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", r.lifecycleStatus);
        verify(records, never()).update(any(), any());

        // 非待签状态（已签）→ 422
        r.lifecycleStatus = "SIGNED";
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰")).getCode());

        // T4-I4：归档异常残留 CONFIRMED+PENDING_CLIENT_SIGNATURE → 不得拒签回滚已签
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.confirmStatus = "CONFIRMED";
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰")).getCode());
        verify(records, never()).update(any(), any());

        // 待签 + 未确认 + 原因齐备 → 条件更新命中，REFUSED_RETURNED + 拒签原因留痕
        r.confirmStatus = "PENDING";
        when(records.update(any(), any())).thenReturn(1);
        real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰");
        assertEquals("REFUSED_RETURNED", r.lifecycleStatus);
        assertEquals("照片不清晰", r.refuseReason);

        // T4-I4：条件更新 0 行（并发已签抢先落库）→ 422，实体状态不改写
        MaintainRecord race = signable();
        race.shareToken = "sg-token";
        when(records.update(any(), any())).thenReturn(0);
        assertEquals(422, assertThrows(BizException.class,
                () -> real.refuseByToken(RECORD_ID, "sg-token", "照片不清晰")).getCode());
        assertEquals("PENDING_CLIENT_SIGNATURE", race.lifecycleStatus);
        assertEquals("内部复核退回原因", race.refuseReason); // 拒签原因未写入（fixture 原值不被改写）
    }
}
