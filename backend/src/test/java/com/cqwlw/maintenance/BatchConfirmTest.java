package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.UnitRecordService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ≤20 条、签名先于循环校验、越权/状态失败逐条入 failed[]。 */
class BatchConfirmTest {

    private static final Set<String> CLIENT_ROLES = Set.of("CLIENT_ADMIN");
    private static final String UNIT = "uu_1";

    private MaintainRecordMapper recordMapper;
    private UnitRecordService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
        recordMapper = mock(MaintainRecordMapper.class);
        service = new UnitRecordService(recordMapper, mock(WorkOrderService.class),
                mock(FileStorageService.class), mock(ElevatorMapper.class));
        when(recordMapper.selectCount(any())).thenReturn(0L); // 全部最新版本
        when(recordMapper.update(isNull(), any())).thenReturn(1); // 条件更新命中（先签为准）
    }

    private MaintainRecord pendingOf(String id, String useUnitId) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.useUnitId = useUnitId;
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.confirmStatus = "PENDING";
        when(recordMapper.selectById(id)).thenReturn(r);
        return r;
    }

    private static Map<String, Object> signBody() {
        return Map.of("signatureFileId", "file_sign_1", "signatureUrl", "/files/file_sign_1");
    }

    @Test
    void signsAllWhenEveryRecordSignable() {
        pendingOf("ur_1", UNIT);
        pendingOf("ur_2", UNIT);
        Map<String, Object> out = service.batchConfirmAs(List.of("ur_1", "ur_2"), signBody(),
                CLIENT_ROLES, UNIT, "ca_1", "王芳");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(2, out.get("signed"));
        assertTrue(((List<?>) out.get("failed")).isEmpty());
    }

    @Test
    void partialFailureDoesNotBlockOthers() {
        MaintainRecord ok = pendingOf("ur_1", UNIT);
        MaintainRecord foreign = pendingOf("ur_2", "uu_2"); // 跨单位 → 1403
        MaintainRecord signedAlready = pendingOf("ur_3", UNIT);
        signedAlready.confirmStatus = "CONFIRMED"; // 已签 → 422
        Map<String, Object> out = service.batchConfirmAs(List.of("ur_1", "ur_2", "ur_3"), signBody(),
                CLIENT_ROLES, UNIT, "ca_1", "王芳");
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertEquals(1, out.get("signed"));
        assertEquals("CONFIRMED", ok.confirmStatus);
        List<?> failed = (List<?>) out.get("failed");
        assertEquals(2, failed.size());
        assertEquals("PENDING", foreign.confirmStatus); // 越权条无副作用
    }

    @Test
    void validatesInputBeforeLoop() {
        assertThrows(BizException.class,
                () -> service.batchConfirmAs(List.of(), signBody(), CLIENT_ROLES, UNIT, null, null));
        assertThrows(BizException.class,
                () -> service.batchConfirmAs(List.of("ur_1"), Map.of(), CLIENT_ROLES, UNIT, null, null));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("ur_" + i);
        }
        BizException e = assertThrows(BizException.class,
                () -> service.batchConfirmAs(tooMany, signBody(), CLIENT_ROLES, UNIT, null, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("20"), e.getMessage());
    }

    @Test
    void requiresClientPrincipalAndAnchor() {
        pendingOf("ur_1", UNIT);
        assertThrows(BizException.class, () -> service.batchConfirmAs(List.of("ur_1"), signBody(),
                Set.of("MAINT_TECH"), UNIT, null, null));
        assertThrows(BizException.class, () -> service.batchConfirmAs(List.of("ur_1"), signBody(),
                CLIENT_ROLES, null, null, null));
    }
}
