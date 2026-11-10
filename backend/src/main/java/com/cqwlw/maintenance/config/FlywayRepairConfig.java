package com.cqwlw.maintenance.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Flyway 启动策略：默认仅 migrate，并在 migrate 前清掉「失败迁移」的历史行；repair 仍需显式设 FLYWAY_REPAIR=true。
 *
 * 背景一：MySQL 5.7 DDL 无事务，迁移中断会在 flyway_schema_history 留下 success=0 的行，
 * 之后每次启动都在 Validate 阶段直接失败，应用永远起不来（2026-10-05 V19、2026-11-10 V22 云托管两次踩中）。
 * 云托管环境不一定能拿到数据库写权限，所以启动前自动删除 success=0 的行，让这条迁移重跑一次。
 *
 * 背景二（M6）：无条件 repair 会把已上线迁移的 checksum 改写为当前脚本值、掩盖被篡改的迁移，故 repair 仍默认关闭；
 * 这里只删失败行，不碰已成功迁移的 checksum，校验口径不变。
 */
@Configuration
public class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    private static final String SELECT_FAILED_SQL =
            "SELECT installed_rank, version, description FROM flyway_schema_history WHERE success = 0";
    /** 失败记录逐条按主键删（installed_rank 是主键），避免 sql_safe_updates=1 时被服务端拒绝。 */
    private static final String DELETE_FAILED_SQL =
            "DELETE FROM flyway_schema_history WHERE installed_rank = ?";
    /** MySQL 5.7：历史表不存在（首次部署） */
    private static final int TABLE_NOT_FOUND = 1146;

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            if (repairEnabled()) {
                flyway.repair();
            } else {
                clearFailedMigrations(dataSource);
            }
            flyway.migrate();
        };
    }

    private static boolean repairEnabled() {
        String flag = System.getenv("FLYWAY_REPAIR");
        if (flag == null) {
            flag = System.getProperty("flyway.repair");
        }
        return Boolean.parseBoolean(flag);
    }

    /**
     * 删除 success=0 的历史行，使被中断的迁移能在本次启动重跑。
     * 只做 DELETE，不做 checksum 对齐；删除失败不阻断启动，交回 Flyway 输出原始校验错误。
     */
    void clearFailedMigrations(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            List<Long> ranks = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_FAILED_SQL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ranks.add(rs.getLong(1));
                    labels.add(rs.getString(2) + " (" + rs.getString(3) + ")");
                }
            }
            if (ranks.isEmpty()) {
                return;
            }
            int deleted = 0;
            try (PreparedStatement ps = conn.prepareStatement(DELETE_FAILED_SQL)) {
                for (Long rank : ranks) {
                    ps.setLong(1, rank);
                    deleted += ps.executeUpdate();
                }
            }
            log.warn("清理 {} 条失败迁移记录 {}，本次启动将重跑；已成功迁移的 checksum 仍严格校验（M6）", deleted, labels);
        } catch (SQLException e) {
            if (e.getErrorCode() == TABLE_NOT_FOUND) {
                return;
            }
            log.warn("清理失败迁移记录未完成，交回 Flyway 校验：{}（errorCode={}）", e.getMessage(), e.getErrorCode());
        }
    }
}
