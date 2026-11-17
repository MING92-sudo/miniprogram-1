package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 维保记录读路径照片归一（2026-11-10 缺陷）：
 * 旧记录可能已上传 photoFileIds，但 items/photos_json 仍冻结为 wxfile://、http://tmp/ 本地路径；
 * 记录详情/管理端预览必须按 fileId 重签，否则照片只在拍照设备可见。
 */
class UnitRecordServiceTest {

    private FileStorageService fileStorage;
    private MaintainRecordMapper recordMapper;
    private UnitRecordService service;

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorageService.class);
        recordMapper = mock(MaintainRecordMapper.class);
        service = new UnitRecordService(recordMapper,
                mock(WorkOrderService.class), fileStorage,
                mock(com.cqwlw.maintenance.mapper.ElevatorMapper.class));
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

    /**
     * P1 T8-A3：signView 投影 recordVersion/hashPrefix（sign 页「签署前核对」契约字段，
     * 与 mock sign-view 对齐）；hashPrefix = 归档 SHA-256 前 8 位，未归档为空串；
     * 白名单不变：不回显 shareToken、不返回 satisfaction（docs/14 §4.7）。
     */
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

    /**
     * C-24：toMap 内部投影补生命周期/版本链字段（GET /records/{id} 员工链路，record-detail 页消费）；
     * hashPrefix = 归档 SHA-256 前 8 位（与 signView 同口径）；shareToken 仍在内部投影（copySignLink 依赖）。
     * 客户路径不受影响：toClientMap 白名单由 ClientRecordGateTest 锁定。
     */
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

    /** C-24：未归档（archiveHash 空）→ toMap.hashPrefix 空串，不报错（与 signView 同口径） */
    @Test
    void toMapHashPrefixEmptyBeforeArchive() {
        Map<String, Object> out = service.toMap(record());

        assertEquals("", out.get("hashPrefix"));
        assertTrue(out.containsKey("lifecycleStatus"));
        assertTrue(out.containsKey("version"));
        assertTrue(out.containsKey("prevVersionId"));
        // final-fix Minor#2：未归档 archivedAt 为 null → 投影收敛空串（不抛错、不回 null）
        assertEquals("", out.get("archivedAt"));
    }

    /**
     * final-fix Minor#2（2026-11-17）：toMap 补 archivedAt 投影——record-detail「版本核对」行的
     * 「版本生成时刻」此前只读 mock 专用字段 frozenAt，真实后端 toMap 不投影任何时刻字段致恒空串；
     * 归档时同时落 archiveHash（hashPrefix 来源）与 archivedAt，故版本核对行时间口径取 archivedAt
     * （yyyy-MM-dd HH:mm:ss，GMT+8，AGENTS §4.1），前端保留 frozenAt 兜底兼容 mock 种子。
     */
    @Test
    void toMapProjectsArchivedAt() {
        MaintainRecord r = record();
        r.archivedAt = LocalDateTime.of(2026, 11, 17, 10, 30, 0);

        Map<String, Object> out = service.toMap(r);

        assertEquals("2026-11-17 10:30:00", out.get("archivedAt"));
    }

    private static MaintainRecord record() {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.confirmStatus = "PENDING";
        return r;
    }
}
