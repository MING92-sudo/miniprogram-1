package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.Map;

/**
 * 文件上传（wx.uploadFile 兜底端点，docs/04 A.0.1）：照片/签名代理上传 COS（Q6=A）。
 */
@RestController
public class FileController {

    private final FileStorageService fileStorageService;
    private final AppFileMapper fileMapper;

    public FileController(FileStorageService fileStorageService, AppFileMapper fileMapper) {
        this.fileStorageService = fileStorageService;
        this.fileMapper = fileMapper;
    }

    @PostMapping("/files/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   HttpServletRequest request) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BizException(422, "缺少上传文件");
        }
        String schemeHost = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        AppFile f = fileStorageService.store(file, schemeHost);
        return ApiResponse.ok(Map.of("fileId", f.id, "url", f.url == null ? "" : f.url));
    }

    /** 本地回退读取（COS 模式前端直接用 url，不会走到这里） */
    @GetMapping("/files/{id}")
    public ResponseEntity<FileSystemResource> serve(@PathVariable String id) throws Exception {
        AppFile f = fileMapper.selectById(id);
        if (f == null || f.url != null && f.url.startsWith("http")) {
            throw new BizException(1404, "文件不存在");
        }
        var path = fileStorageService.localPath(f);
        if (!Files.exists(path)) {
            throw new BizException(1404, "文件不存在");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        f.contentType == null ? "application/octet-stream" : f.contentType))
                .body(new FileSystemResource(path));
    }
}
