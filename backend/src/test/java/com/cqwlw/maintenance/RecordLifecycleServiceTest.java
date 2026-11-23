package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.RecordPdfService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 归档幂等可重入：前置门禁（ARCHIVED 必经 SIGNED）、已归档短路、并发落败方只清理自存文件。 */
class RecordLifecycleServiceTest {

    private MaintainRecordMapper recordMapper;
    private RecordPdfService pdfService;
    private FileStorageService fileStorage;
    private RecordLifecycleService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
    }

    @BeforeEach
    void setUp() {
        recordMapper = mock(MaintainRecordMapper.class);
        pdfService = mock(RecordPdfService.class);
        fileStorage = mock(FileStorageService.class);
        service = new RecordLifecycleService(recordMapper, mock(WorkOrderMapper.class),
                mock(EmployeeMapper.class), mock(ElevatorMapper.class), mock(UseUnitMapper.class),
                pdfService, fileStorage, mock(SysConfigMapper.class));
    }

    private static MaintainRecord record(String lifecycleStatus, String confirmStatus) {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.elevatorCode = "DT-1";
        r.lifecycleStatus = lifecycleStatus;
        r.confirmStatus = confirmStatus;
        return r;
    }

    @Test
    void archiveRejectsUnsignedRecord() {
        MaintainRecord r = record("PENDING_CLIENT_SIGNATURE", "CONFIRMED");

        BizException e = assertThrows(BizException.class, () -> service.archive(r));

        assertEquals(422, e.getCode());
        verify(fileStorage, never()).storeBytes(any(), any(), any(), any());
        verify(recordMapper, never()).update(any(), any());
    }

    @Test
    void archiveShortCircuitsWhenAlreadyArchived() {
        MaintainRecord r = record("ARCHIVED", "CONFIRMED");
        r.archiveFileId = "file_old";

        service.archive(r);

        verify(fileStorage, never()).storeBytes(any(), any(), any(), any());
        verify(recordMapper, never()).update(any(), any());
    }

    @Test
    void archiveLoserDeletesOwnStoredFile() {
        MaintainRecord r = record("SIGNED", "CONFIRMED");
        when(pdfService.render(any(), any(), any())).thenReturn(new byte[]{1});
        AppFile stored = new AppFile();
        stored.id = "file_new";
        when(fileStorage.storeBytes(any(), any(), any(), any())).thenReturn(stored);
        when(recordMapper.update(any(), any())).thenReturn(0); // 并发归档落败

        service.archive(r);

        // 落败方只删自己刚存的文件（库中 winner 的 archiveFileId 绝不可碰）
        verify(fileStorage, times(1)).deleteFile(stored.id);
        assertNull(r.archiveFileId);
        assertEquals("SIGNED", r.lifecycleStatus);
    }
}
