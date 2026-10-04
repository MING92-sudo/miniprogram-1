package com.cqwlw.maintenance.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/**
 * Flyway 启动策略：先 repair 再 migrate。
 * 背景：MySQL 5.7 DDL 无事务，V19 首次执行半途失败后每次启动 Validate 均失败（2026-10-05 云托管）；
 * repair 自动清除 schema history 中的失败记录并对齐 checksum，迁移脚本本身已改为幂等。
 */
@Configuration
public class FlywayRepairConfig {
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
