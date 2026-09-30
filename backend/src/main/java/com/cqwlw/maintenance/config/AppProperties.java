package com.cqwlw.maintenance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用级配置；生产环境敏感项全部通过环境变量注入。 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Jwt jwt = new Jwt();
    private String fileRoot = "./data/files";
    private int workDurationMinutes = 30;

    public static class Jwt {
        private String secret = "change-me-before-production";
        private int ttlHours = 12;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public String getFileRoot() {
        return fileRoot;
    }

    public void setFileRoot(String fileRoot) {
        this.fileRoot = fileRoot;
    }

    public int getWorkDurationMinutes() {
        return workDurationMinutes;
    }

    public void setWorkDurationMinutes(int workDurationMinutes) {
        this.workDurationMinutes = workDurationMinutes;
    }
}
