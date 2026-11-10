package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PDF 抓图按 /files/{id}?s=... 回源：签名参数不得被当成主键的一部分，
 * 否则导出时照片/签字全部退化成占位文字。
 */
class RecordPdfSignedUrlTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    private AppFileMapper fileMapper;
    private FileStorageService fileStorage;
    private RecordPdfService service;

    @BeforeEach
    void setUp() {
        fileMapper = mock(AppFileMapper.class);
        fileStorage = mock(FileStorageService.class);
        AppFile f = new AppFile();
        f.id = "file_1";
        f.url = "http://localhost:8080/files/file_1";
        when(fileMapper.selectById("file_1")).thenReturn(f);
        when(fileStorage.readBytes(f)).thenReturn(IMAGE);
        service = new RecordPdfService(new RestTemplate(), fileMapper, fileStorage, mock(CompanyMapper.class));
    }

    @Test
    void stripsSignatureQueryFromAbsoluteUrl() {
        byte[] out = service.loadImage("http://localhost:8080/files/file_1?s=1948179075.abcdef0123");
        assertArrayEquals(IMAGE, out);
        verify(fileMapper).selectById("file_1");
    }

    @Test
    void stripsSignatureQueryFromRelativeUrl() {
        byte[] out = service.loadImage("/files/file_1?s=1948179075.abcdef0123");
        assertArrayEquals(IMAGE, out);
        verify(fileMapper).selectById("file_1");
    }

    @Test
    void missingFileStillDegradesToNull() {
        assertNull(service.loadImage("http://localhost:8080/files/file_missing?s=1.2"));
    }

    @Test
    void pdfPhotosRecoverOldRecordsFromPhotoFileIds() {
        MaintainRecord record = new MaintainRecord();
        record.photosJson = JsonUtil.write(List.of("wxfile://tmp_old.jpg"));
        when(fileStorage.urlOf("file_1"))
                .thenReturn("http://localhost:8080/files/file_1?s=fresh");

        List<String> urls = service.recordPhotoUrls(record, List.of(Map.of(
                "photos", List.of("wxfile://tmp_old.jpg"),
                "photoFileIds", List.of("file_1"))));

        assertEquals(List.of("http://localhost:8080/files/file_1?s=fresh"), urls);
    }

    @Test
    void pdfPhotosDropUnrecoverableLocalPaths() {
        MaintainRecord record = new MaintainRecord();
        record.photosJson = JsonUtil.write(List.of("http://tmp/old.png"));

        List<String> urls = service.recordPhotoUrls(record, List.of(Map.of(
                "photos", List.of("http://tmp/old.png"))));

        assertTrue(urls.isEmpty());
    }
}
