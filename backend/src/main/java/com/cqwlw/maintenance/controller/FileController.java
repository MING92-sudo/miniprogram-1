package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.CurrentUser;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.service.FileAccessTokenService;
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
 * 文件上传（wx.uploadFile 兜底端点）：照片/签名代理上传 COS。
 */
@RestController
public class FileController {

    private final FileStorageService fileStorageService;
    private final AppFileMapper fileMapper;
    private final CurrentUser currentUser;
    private final FileAccessTokenService fileToken;

    public FileController(FileStorageService fileStorageService, AppFileMapper fileMapper,
                          CurrentUser currentUser, FileAccessTokenService fileToken) {
        this.fileStorageService = fileStorageService;
        this.fileMapper = fileMapper;
        this.currentUser = currentUser;
        this.fileToken = fileToken;
    }

    @PostMapping("/files/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   HttpServletRequest request) throws Exception {
        // 写侧必须登录态：否则任何人可匿名上传任意文件（占满存储、并把攻击者内容留在本系统域名下）
        currentUser.requireEmployeeId();
        if (file == null || file.isEmpty()) {
            throw new BizException(422, "缺少上传文件");
        }
        String schemeHost = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        AppFile f = fileStorageService.store(file, schemeHost);
        return ApiResponse.ok(Map.of("fileId", f.id, "url", f.url == null ? "" : f.url));
    }

    /** 本地回退读取（COS 模式前端直接用 url，不会走到这里）；需带签名，允许匿名以便使用单位签字页渲染 */
    @GetMapping("/files/{id}")
    public ResponseEntity<FileSystemResource> serve(@PathVariable String id,
                                                    @RequestParam(name = "s", required = false) String signature)
            throws Exception {
        if (!fileToken.verify(id, signature)) {
            throw new BizException(403, "文件访问签名无效");
        }
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

    /** 直传元数据（POST /files/sts）：返回存储模式与直传元数据，真实 STS 签发为云端 CAM 部署项 */
    @PostMapping("/files/sts")
    public ApiResponse<Map<String, Object>> sts(@org.springframework.web.bind.annotation.RequestBody(required = false)
                                                Map<String, Object> body,
                                                HttpServletRequest request) {
        // 直传凭证等同于写权限，必须登录态
        currentUser.requireEmployeeId();
        String dir = body == null || body.get("dir") == null ? "" : String.valueOf(body.get("dir"));
        int maxAge = body == null || body.get("maxAge") == null ? 1800
                : Integer.parseInt(String.valueOf(body.get("maxAge")));
        String schemeHost = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        return ApiResponse.ok(fileStorageService.stsDirective(dir, maxAge, schemeHost));
    }
}
