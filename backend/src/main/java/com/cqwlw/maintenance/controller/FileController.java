package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
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

    /** 同源文件下发：本地文件直读；COS 模式经内网凭证流式拉取（托管桶私有读，直链不可访问） */
    @GetMapping("/files/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable String id) throws Exception {
        AppFile f = fileMapper.selectById(id);
        if (f == null) {
            throw new BizException(1404, "文件不存在");
        }
        byte[] data = fileStorageService.readBytes(f);
        if (data == null) {
            throw new BizException(1404, "文件不存在");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        f.contentType == null ? "application/octet-stream" : f.contentType))
                .contentLength(data.length)
                .body(data);
    }

    /** P4 直传元数据（docs/04 A.6 POST /files/sts）：返回存储模式与直传元数据，真实 STS 签发为云端 CAM 部署项 */
    @PostMapping("/files/sts")
    public ApiResponse<Map<String, Object>> sts(@org.springframework.web.bind.annotation.RequestBody(required = false)
                                                Map<String, Object> body,
                                                HttpServletRequest request) {
        String dir = body == null || body.get("dir") == null ? "" : String.valueOf(body.get("dir"));
        int maxAge = body == null || body.get("maxAge") == null ? 1800
                : Integer.parseInt(String.valueOf(body.get("maxAge")));
        String schemeHost = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        return ApiResponse.ok(fileStorageService.stsDirective(dir, maxAge, schemeHost));
    }
}
