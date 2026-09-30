package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

/**
 * 文件存储（P4 STS 直传前的代理上传方案，Q6=A 决策）：
 * 配置 COS_* 环境变量时上传腾讯云 COS；否则回退本地磁盘（仅本地开发）。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final AppProperties props;
    private final AppFileMapper fileMapper;
    private volatile COSClient cosClient;

    public FileStorageService(AppProperties props, AppFileMapper fileMapper) {
        this.props = props;
        this.fileMapper = fileMapper;
    }

    @PostConstruct
    public void init() {
        if (props.cosConfigured()) {
            COSCredentials cred = new BasicCOSCredentials(props.getCosSecretId(), props.getCosSecretKey());
            ClientConfig cfg = new ClientConfig(new Region(props.getCosRegion()));
            cfg.setHttpProtocol(HttpProtocol.https);
            cosClient = new COSClient(cred, cfg);
            log.info("文件存储: 腾讯云 COS（bucket={}, region={}）", props.getCosBucket(), props.getCosRegion());
        } else {
            log.info("文件存储: 本地磁盘（{}，仅本地开发用）", props.getFileStorageDir());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (cosClient != null) {
            cosClient.shutdown();
        }
    }

    /** 上传并落库，返回 { fileId, url }（前端 uploadImage 契约） */
    public AppFile store(MultipartFile file, String schemeHost) throws IOException {
        String ext = extOf(file.getOriginalFilename());
        String id = Ids.next("file");
        String objectKey = LocalDate.now(TimeUtil.ZONE) + "/" + id + ext;
        String url;
        if (cosClient != null) {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(file.getSize());
            meta.setContentType(file.getContentType());
            cosClient.putObject(props.getCosBucket(), objectKey, file.getInputStream(), meta);
            url = "https://" + props.getCosBucket() + ".cos." + props.getCosRegion()
                    + ".myqcloud.com/" + objectKey;
        } else {
            Path dir = Paths.get(props.getFileStorageDir());
            Files.createDirectories(dir);
            Path target = dir.resolve(objectKey.replace('/', '_'));
            file.transferTo(target.toAbsolutePath().toFile());
            url = schemeHost + "/files/" + id;
        }
        AppFile f = new AppFile();
        f.id = id;
        f.objectKey = objectKey;
        f.url = url;
        f.contentType = file.getContentType();
        f.sizeBytes = file.getSize();
        f.createdAt = TimeUtil.now();
        fileMapper.insert(f);
        return f;
    }

    /** 本地回退：按 fileId 读回字节（COS 模式前端直接用 url） */
    public Path localPath(AppFile f) {
        return Paths.get(props.getFileStorageDir(), f.objectKey.replace('/', '_'))
                .toAbsolutePath();
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(dot) : "";
    }
}
