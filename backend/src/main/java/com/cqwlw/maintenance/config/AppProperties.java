package com.cqwlw.maintenance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自建应用配置（环境变量注入，禁止真实凭证入库，AGENTS §2）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String jwtSecret;
    private int jwtExpireHours = 72;
    private String devOpenid = "dev_openid";
    private boolean seedDemoData = true;
    private String cosSecretId;
    private String cosSecretKey;
    private String cosRegion;
    private String cosBucket;
    private String cosAuthUrl = "http://api.weixin.qq.com/_/cos/getauth";
    private String fileStorageDir = "./data/files";
    private String lbsAmapKey;
    private String lbsTencentKey;

    public boolean cosConfigured() {
        return cosBucket != null && !cosBucket.isEmpty()
                && cosRegion != null && !cosRegion.isEmpty();
    }

    /** 静态密钥仅自建 COS 桶使用；云托管托管桶走内网临时凭证（cosAuthUrl） */
    public boolean cosStaticCredentialConfigured() {
        return cosSecretId != null && !cosSecretId.isEmpty()
                && cosSecretKey != null && !cosSecretKey.isEmpty();
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public int getJwtExpireHours() {
        return jwtExpireHours;
    }

    public void setJwtExpireHours(int jwtExpireHours) {
        this.jwtExpireHours = jwtExpireHours;
    }

    public String getDevOpenid() {
        return devOpenid;
    }

    public void setDevOpenid(String devOpenid) {
        this.devOpenid = devOpenid;
    }

    public boolean isSeedDemoData() {
        return seedDemoData;
    }

    public void setSeedDemoData(boolean seedDemoData) {
        this.seedDemoData = seedDemoData;
    }

    public String getCosSecretId() {
        return cosSecretId;
    }

    public void setCosSecretId(String cosSecretId) {
        this.cosSecretId = cosSecretId;
    }

    public String getCosSecretKey() {
        return cosSecretKey;
    }

    public void setCosSecretKey(String cosSecretKey) {
        this.cosSecretKey = cosSecretKey;
    }

    public String getCosRegion() {
        return cosRegion;
    }

    public void setCosRegion(String cosRegion) {
        this.cosRegion = cosRegion;
    }

    public String getCosBucket() {
        return cosBucket;
    }

    public void setCosBucket(String cosBucket) {
        this.cosBucket = cosBucket;
    }

    public String getCosAuthUrl() {
        return cosAuthUrl;
    }

    public void setCosAuthUrl(String cosAuthUrl) {
        this.cosAuthUrl = cosAuthUrl;
    }

    public String getFileStorageDir() {
        return fileStorageDir;
    }

    public void setFileStorageDir(String fileStorageDir) {
        this.fileStorageDir = fileStorageDir;
    }

    public String getLbsAmapKey() {
        return lbsAmapKey;
    }

    public void setLbsAmapKey(String lbsAmapKey) {
        this.lbsAmapKey = lbsAmapKey;
    }

    public String getLbsTencentKey() {
        return lbsTencentKey;
    }

    public void setLbsTencentKey(String lbsTencentKey) {
        this.lbsTencentKey = lbsTencentKey;
    }
}
