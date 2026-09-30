package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.auth.COSStaticCredentialsProvider;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

/**
 * 文件存储（P4 STS 直传前的代理上传方案，Q6=A 决策）：
 * 配置 COS_BUCKET/COS_REGION 时上传腾讯云 COS；否则回退本地磁盘（仅本地开发）。
 * 云托管托管桶无静态密钥，走云托管内网临时凭证（COS_AUTH_URL=/_/cos/getauth）；
 * 自建 COS 桶（本地联调）可注入 COS_SECRET_ID/COS_SECRET_KEY。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    /** 临时凭证提前 5 分钟刷新，避免临界过期（口径对齐 docs/04 B token 中控） */
    private static final long EXPIRY_SLACK_SECONDS = 300;

    private final AppProperties props;
    private final AppFileMapper fileMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private volatile COSClient cosClient;

    public FileStorageService(AppProperties props, AppFileMapper fileMapper,
                              RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.props = props;
        this.fileMapper = fileMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (props.cosConfigured()) {
            ClientConfig cfg = new ClientConfig(new Region(props.getCosRegion()));
            cfg.setHttpProtocol(HttpProtocol.https);
            if (props.cosStaticCredentialConfigured()) {
                COSCredentials cred = new BasicCOSCredentials(props.getCosSecretId(), props.getCosSecretKey());
                cosClient = new COSClient(new COSStaticCredentialsProvider(cred), cfg);
                log.info("文件存储: 腾讯云 COS（bucket={}, region={}，静态密钥）",
                        props.getCosBucket(), props.getCosRegion());
            } else {
                cosClient = new COSClient(new WxCloudRunSessionCredentialsProvider(), cfg);
                log.info("文件存储: 腾讯云 COS（bucket={}, region={}，云托管内网临时凭证）",
                        props.getCosBucket(), props.getCosRegion());
            }
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

    /**
     * 云托管内网临时凭证提供器：GET /cos/getauth 换取临时密钥，缓存至过期前 5 分钟。
     * 该内网地址仅云托管容器内可达；本地开发请配置静态密钥或保持本地磁盘回退。
     */
    private final class WxCloudRunSessionCredentialsProvider implements COSCredentialsProvider {

        private volatile BasicSessionCredentials cached;
        private volatile long refreshAtMillis;

        @Override
        public COSCredentials getCredentials() {
            if (cached != null && System.currentTimeMillis() < refreshAtMillis) {
                return cached;
            }
            synchronized (this) {
                if (cached == null || System.currentTimeMillis() >= refreshAtMillis) {
                    fetchAndCache();
                }
                return cached;
            }
        }

        private void fetchAndCache() {
            try {
                String body = restTemplate.getForObject(props.getCosAuthUrl(), String.class);
                JsonNode n = objectMapper.readTree(body == null ? "{}" : body);
                long expiredSeconds = n.path("ExpiredTime").asLong(System.currentTimeMillis() / 1000 + 1800);
                cached = new BasicSessionCredentials(
                        n.path("TmpSecretId").asText(),
                        n.path("TmpSecretKey").asText(),
                        n.path("Token").asText());
                refreshAtMillis = Math.max(System.currentTimeMillis(),
                        (expiredSeconds - EXPIRY_SLACK_SECONDS) * 1000);
            } catch (Exception e) {
                throw new IllegalStateException("获取云托管对象存储临时凭证失败（仅云托管容器内可用）: " + e.getMessage(), e);
            }
        }

        @Override
        public void refresh() {
            synchronized (this) {
                cached = null;
                refreshAtMillis = 0;
            }
        }
    }
}
