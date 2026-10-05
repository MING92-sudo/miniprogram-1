package com.cqwlw.maintenance.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/**
 * Flyway 启动策略：默认仅 migrate；repair 需显式设 FLYWAY_REPAIR=true。
 * 背景：MySQL 5.7 DDL 无事务，V19 首次执行半途失败后每次启动 Validate 均失败（2026-10-05 云托管）；
 * repair 自动清除 schema history 中的失败记录并对齐 checksum，迁移脚本本身已改为幂等。
 * M6：无条件 repair 会把已上线迁移的 checksum 改写为当前脚本值、掩盖被篡改的迁移，故默认关闭。
 */
@Configuration
public class FlywayRepairConfig {
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            String flag = System.getenv("FLYWAY_REPAIR");
            if (flag == null) {
                flag = System.getProperty("flyway.repair");
            }
            if (Boolean.parseBoolean(flag)) {
                flyway.repair();
            }
            flyway.migrate();
        };
    }
}
