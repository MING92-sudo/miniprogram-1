package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 记录详情/管理端预览必须按 fileId 重签，否则照片只在拍照设备可见。 */
class UnitRecordServiceTest {

    private FileStorageService fileStorage;
    private MaintainRecordMapper recordMapper;
    private ElevatorMapper elevatorMapper;
    private WorkOrderService workOrderService;
    private UnitRecordService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
    }

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorageService.class);
        recordMapper = mock(MaintainRecordMapper.class);
        elevatorMapper = mock(ElevatorMapper.class);
        workOrderService = mock(WorkOrderService.class);
        when(workOrderService.paginate(any(), any())).thenAnswer(inv -> {
            List<?> list = inv.getArgument(0);
            return Map.of("list", list, "total", list.size());
        });
        service = new UnitRecordService(recordMapper, workOrderService, fileStorage, elevatorMapper);
    }

    @Test
    void oldFrozenRecordPhotosResolveFromPhotoFileIds() {
        MaintainRecord r = record();
        r.itemsJson = JsonUtil.write(List.of(Map.of(
                "id", "it_1",
                "name", "曳引机",
                "photos", List.of("wxfile://tmp_a.jpg"),
                "photoFileIds", List.of("file_old"))));
        r.photosJson = JsonUtil.write(List.of("wxfile://tmp_a.jpg"));
        when(fileStorage.urlOf("file_old"))
                .thenReturn("https://api.example.com/files/file_old?s=fresh");

        Map<String, Object> out = service.toMap(r);

        assertEquals(List.of("https://api.example.com/files/file_old?s=fresh"), out.get("photos"));
        List<?> items = (List<?>) out.get("items");
        assertEquals(List.of("https://api.example.com/files/file_old?s=fresh"),
                ((Map<?, ?>) items.get(0)).get("photos"));
    }

    @Test
    void localOnlyTemporaryPathsAreNotExposed() {
        MaintainRecord r = record();
        r.itemsJson = JsonUtil.write(List.of(Map.of(
                "id", "it_1",
                "name", "曳引机",
                "photos", List.of("http://tmp/broken.png"))));
        r.photosJson = JsonUtil.write(List.of("http://tmp/broken.png"));

        Map<String, Object> out = service.toMap(r);

        assertTrue(((List<?>) out.get("photos")).isEmpty());
        List<?> items = (List<?>) out.get("items");
        assertTrue(((List<?>) ((Map<?, ?>) items.get(0)).get("photos")).isEmpty());
    }

    /** 白名单不变：不回显 shareToken、不返回 satisfaction。 */
    @Test
    void signViewProjectsRecordVersionAndHashPrefix() {
        MaintainRecord r = record();
        r.shareToken = "sg-1";
        r.version = 2;
        r.archiveHash = "9f3c7a21b8d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e";
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        Map<String, Object> out = service.signView("ur_1", "sg-1");

        assertEquals(2, out.get("recordVersion"));
        assertEquals("9f3c7a21", out.get("hashPrefix"));
        assertFalse(out.containsKey("shareToken"));
        assertFalse(out.containsKey("satisfaction"));
    }

    /** 未归档（archiveHash 为空）→ hashPrefix 空串，不报错 */
    @Test
    void signViewHashPrefixEmptyBeforeArchive() {
        MaintainRecord r = record();
        r.shareToken = "sg-1";
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        Map<String, Object> out = service.signView("ur_1", "sg-1");

        assertEquals("", out.get("hashPrefix"));
    }

    /** shareToken 仍在内部投影（copySignLink 依赖）。 */
    @Test
    void toMapProjectsLifecycleAndVersionChain() {
        MaintainRecord r = record();
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.version = 2;
        r.prevVersionId = "ur_0";
        r.shareToken = "sg-1";
        r.archiveHash = "9f3c7a21b8d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e";

        Map<String, Object> out = service.toMap(r);

        assertEquals("PENDING_CLIENT_SIGNATURE", out.get("lifecycleStatus"));
        assertEquals(2, out.get("version"));
        assertEquals("ur_0", out.get("prevVersionId"));
        assertEquals("9f3c7a21", out.get("hashPrefix"));
        assertEquals("sg-1", out.get("shareToken"));
    }

    @Test
    void toMapHashPrefixEmptyBeforeArchive() {
        Map<String, Object> out = service.toMap(record());

        assertEquals("", out.get("hashPrefix"));
        assertTrue(out.containsKey("lifecycleStatus"));
        assertTrue(out.containsKey("version"));
        assertTrue(out.containsKey("prevVersionId"));
        assertEquals("", out.get("archivedAt"));
    }

    @Test
    void toMapProjectsArchivedAt() {
        MaintainRecord r = record();
        r.archivedAt = LocalDateTime.of(2026, 11, 17, 10, 30, 0);

        Map<String, Object> out = service.toMap(r);

        assertEquals("2026-11-17 10:30:00", out.get("archivedAt"));
    }

    @Test
    void getAsCrossUnitBySnapshot1403() {
        MaintainRecord r = record();
        r.elevatorCode = "DT-1";
        r.useUnitId = "uuB";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        Elevator el = new Elevator();
        el.elevatorCode = "DT-1";
        el.useUnitId = "uuA"; // 旧实现回查电梯会误判为 uuA 本单位 → 放行（本测试须 RED）
        when(elevatorMapper.selectOne(any())).thenReturn(el);

        BizException ex = assertThrows(BizException.class,
                () -> service.getAs("ur_1", Set.of("CLIENT_ADMIN"), "uuA"));

        assertEquals(1403, ex.getCode());
        verify(recordMapper, never()).update(any(), any());
        verify(recordMapper, never()).updateById(any(MaintainRecord.class));
    }

    @Test
    void pendingAsUsesSnapshotColumnWithoutElevatorQuery() {
        MaintainRecord own = record();
        own.useUnitId = "uuA";
        own.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        MaintainRecord nullSnapshot = record();
        nullSnapshot.id = "ur_null";
        nullSnapshot.useUnitId = null;
        nullSnapshot.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        MaintainRecord foreign = record();
        foreign.id = "ur_foreign";
        foreign.useUnitId = "uuB";
        foreign.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        when(recordMapper.selectList(any())).thenReturn(List.of(own, nullSnapshot, foreign));

        Map<String, Object> out = service.pendingAs(Map.of(), Set.of("CLIENT_ADMIN"), "uuA");

        List<?> list = (List<?>) out.get("list");
        assertEquals(List.of("ur_1"), list.stream().map(m -> ((Map<?, ?>) m).get("id")).toList());
        verify(elevatorMapper, never()).selectList(any());
        verify(elevatorMapper, never()).selectOne(any());
    }

    /** 禁令字段（shareToken/reportPayload/context/satisfaction/refuseReason）仍不返回。 */
    @Test
    void signViewProjectsItemsForClientCheck() {
        MaintainRecord r = record();
        r.shareToken = "sg-1";
        r.useUnitName = "重庆世纪物业管理有限公司";
        r.checkinTime = LocalDateTime.of(2026, 11, 20, 8, 52, 0);
        r.checkoutTime = LocalDateTime.of(2026, 11, 20, 11, 20, 0);
        r.itemsJson = JsonUtil.write(List.of(
                Map.of("name", "曳引机", "result", "NORMAL", "freqLabel", "半月"),
                Map.of("name", "报警装置", "result", "ABNORMAL", "abnormalDesc", "通话杂音大，已复测",
                        "photoFileIds", List.of("file_abn"), "freqLabel", "半月"),
                Map.of("name", "年度试验", "notInThisRun", true, "freqLabel", "年度")));
        when(fileStorage.urlOf("file_abn")).thenReturn("https://api.example.com/files/file_abn?s=fresh");
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        Map<String, Object> out = service.signView("ur_1", "sg-1");

        assertEquals("重庆世纪物业管理有限公司", out.get("useUnitName"));
        assertEquals("2026-11-20", out.get("workDate"));
        assertEquals("PENDING", out.get("confirmStatus"));
        List<?> items = (List<?>) out.get("items");
        assertEquals(2, items.size()); // notInThisRun 未执行项过滤
        assertEquals(2, out.get("itemTotal"));
        Map<?, ?> ab = (Map<?, ?>) items.get(1);
        assertEquals("通话杂音大，已复测", ab.get("abnormalDesc"));
        assertEquals(List.of("https://api.example.com/files/file_abn?s=fresh"), ab.get("photos"));
        assertEquals(List.of("https://api.example.com/files/file_abn?s=fresh"), out.get("photos"));
        assertEquals(1, out.get("photoCount"));
        assertFalse(out.containsKey("shareToken"));
        assertFalse(out.containsKey("reportPayload"));
        assertFalse(out.containsKey("context"));
        assertFalse(out.containsKey("satisfaction"));
        assertFalse(out.containsKey("refuseReason"));
    }

    private static MaintainRecord record() {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.confirmStatus = "PENDING";
        return r;
    }


    @Test
    void externalSignatureUrlRejectedBeforeArchive() {
        MaintainRecord r = signable();
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        RecordLifecycleService lifecycle = mock(RecordLifecycleService.class);
        service.setRecordLifecycleService(lifecycle);

        BizException ex = assertThrows(BizException.class, () -> service.confirmByToken("ur_1", "sg-1",
                Map.of("signatureUrl", "http://127.0.0.1:8080/health")));

        assertEquals(422, ex.getCode());
        verify(recordMapper, never()).update(any(), any());
        verify(lifecycle, never()).onClientSigned(any());
    }

    @Test
    void archiveFailureLeavesRetryableSignedState() {
        MaintainRecord stored = signable();
        RecordPdfService pdf = mock(RecordPdfService.class);
        RecordLifecycleService lifecycle = new RecordLifecycleService(recordMapper, mock(com.cqwlw.maintenance.mapper.WorkOrderMapper.class),
                mock(com.cqwlw.maintenance.mapper.EmployeeMapper.class), elevatorMapper,
                mock(com.cqwlw.maintenance.mapper.UseUnitMapper.class), pdf, fileStorage,
                mock(com.cqwlw.maintenance.mapper.SysConfigMapper.class));
        service.setRecordLifecycleService(lifecycle);
        when(recordMapper.selectById("ur_1")).thenAnswer(inv -> copyRecord(stored));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.update(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MaintainRecord> update =
                    inv.getArgument(1);
            String sqlSet = update.getSqlSet();
            if (sqlSet.contains("confirm_status")) {
                stored.confirmStatus = "CONFIRMED";
            }
            if (sqlSet.contains("lifecycle_status")) {
                stored.lifecycleStatus = sqlSet.contains("archive_file_id") ? "ARCHIVED" : "SIGNED";
            }
            return 1;
        });
        when(pdf.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("PDF 生成失败"))
                .thenReturn(new byte[]{1});
        com.cqwlw.maintenance.entity.AppFile archived = new com.cqwlw.maintenance.entity.AppFile();
        archived.id = "file_archive";
        when(fileStorage.storeBytes(any(), any(), any(), any())).thenReturn(archived);
        when(fileStorage.urlOf("file_archive")).thenReturn("https://x/files/file_archive?s=fresh");

        Map<String, Object> signOut = service.confirmByToken("ur_1", "sg-1",
                Map.of("signatureFileId", "file_client"));

        // 归档失败不拖垮签字：响应 ok + archived=false，签字状态不回滚
        assertEquals(Boolean.TRUE, signOut.get("ok"));
        assertEquals(Boolean.FALSE, signOut.get("archived"));
        assertEquals("CONFIRMED", stored.confirmStatus);
        assertEquals("SIGNED", stored.lifecycleStatus);
        Map<String, Object> out = lifecycle.archiveView("ur_1");
        assertEquals("ARCHIVED", stored.lifecycleStatus);
        assertEquals("https://x/files/file_archive?s=fresh", out.get("url"));
    }

    private static MaintainRecord copyRecord(MaintainRecord source) {
        MaintainRecord copy = new MaintainRecord();
        copy.id = source.id;
        copy.shareToken = source.shareToken;
        copy.confirmStatus = source.confirmStatus;
        copy.lifecycleStatus = source.lifecycleStatus;
        copy.signatureFileId = source.signatureFileId;
        copy.signatureUrl = source.signatureUrl;
        copy.archiveFileId = source.archiveFileId;
        copy.archiveHash = source.archiveHash;
        copy.archivedAt = source.archivedAt;
        return copy;
    }

    private static MaintainRecord signable() {
        MaintainRecord r = record();
        r.shareToken = "sg-1";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        return r;
    }

    private static org.springframework.web.multipart.MultipartFile png() {
        org.springframework.web.multipart.MultipartFile f =
                mock(org.springframework.web.multipart.MultipartFile.class);
        when(f.isEmpty()).thenReturn(false);
        return f;
    }

    /** 合法 token + 可签记录 → 落文件并返回 fileId/url（与 /files/upload 响应契约一致） */
    @Test
    void signatureUploadByTokenStoresFile() throws Exception {
        MaintainRecord r = signable();
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        com.cqwlw.maintenance.entity.AppFile stored = new com.cqwlw.maintenance.entity.AppFile();
        stored.id = "file_sig";
        stored.url = "https://api.example.com/files/file_sig";
        when(fileStorage.store(any(), any())).thenReturn(stored);

        Map<String, Object> out = service.signatureUploadByToken("ur_1", "sg-1", png(), "https://api.example.com");

        assertEquals("file_sig", out.get("fileId"));
        assertEquals("https://api.example.com/files/file_sig", out.get("url"));
    }

    /** 错 token → 401 同文案且无文件写入；记录不存在 → 同 401（无存在性 oracle） */
    @Test
    void signatureUploadByTokenRejectsBeforeWrite() throws Exception {
        MaintainRecord r = signable();
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        BizException wrongToken = assertThrows(BizException.class,
                () -> service.signatureUploadByToken("ur_1", "bad", png(), "https://api.example.com"));
        assertEquals(401, wrongToken.getCode());

        BizException missing = assertThrows(BizException.class,
                () -> service.signatureUploadByToken("ur_none", "sg-1", png(), "https://api.example.com"));
        assertEquals(401, missing.getCode());
        assertEquals(wrongToken.getMessage(), missing.getMessage());
        verify(fileStorage, never()).store(any(), any());
    }

    /** 已签/非待签记录 → 422，不再接受签名图（校验先于写盘） */
    @Test
    void signatureUploadByTokenRequiresSignable() throws Exception {
        MaintainRecord r = signable();
        r.confirmStatus = "CONFIRMED";
        when(recordMapper.selectById("ur_1")).thenReturn(r);

        BizException ex = assertThrows(BizException.class,
                () -> service.signatureUploadByToken("ur_1", "sg-1", png(), "https://api.example.com"));

        assertEquals(422, ex.getCode());
        verify(fileStorage, never()).store(any(), any());
    }

    /** 空文件 → 422 且不落盘 */
    @Test
    void signatureUploadByTokenRejectsEmptyFile() throws Exception {
        MaintainRecord r = signable();
        when(recordMapper.selectById("ur_1")).thenReturn(r);
        org.springframework.web.multipart.MultipartFile empty =
                mock(org.springframework.web.multipart.MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.signatureUploadByToken("ur_1", "sg-1", empty, "https://api.example.com"));

        assertEquals(422, ex.getCode());
        verify(fileStorage, never()).store(any(), any());
    }
}
