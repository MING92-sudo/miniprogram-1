package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.FileRecord;
import com.cqwlw.maintenance.service.FileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        FileRecord record = fileService.store(file);
        return ApiResponse.ok(Map.of("fileId", String.valueOf(record.getId()), "url", record.getUrl()));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        FileRecord record = fileService.get(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(record.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : record.getContentType()));
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(Path.of(record.getStoragePath())));
    }
}
