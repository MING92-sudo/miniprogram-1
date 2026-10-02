package com.cqwlw.maintenance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 监管平台（重庆市智慧特种设备安全管理系统）接入配置。
 * 凭证全部来自环境变量 / .env，严禁硬编码进代码或提交入库。
 */
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    /** 平台认证登录地址 */
    private String authLoginUrl;

    /** 业务接口根路径，例如 https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/ */
    private String apiBaseUrl;

    /** 登录四参数（V1.5 规范 2.1，注意平台通知中可能写作 scret） */
    private String username;
    private String key;
    private String appcode;
    private String secret;

    /** 存量记录接口（2.8）临时启用开关 */
    private boolean legacyUploadEnabled = false;

    public String getAuthLoginUrl() {
        return authLoginUrl;
    }

    public void setAuthLoginUrl(String authLoginUrl) {
        this.authLoginUrl = authLoginUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getAppcode() {
        return appcode;
    }

    public void setAppcode(String appcode) {
        this.appcode = appcode;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isLegacyUploadEnabled() {
        return legacyUploadEnabled;
    }

    public void setLegacyUploadEnabled(boolean legacyUploadEnabled) {
        this.legacyUploadEnabled = legacyUploadEnabled;
    }
}
