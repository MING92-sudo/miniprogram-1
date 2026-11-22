package com.cqwlw.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打包完整性：classpath 下不允许出现同版本的 Flyway 迁移脚本。
 * 背景：2026-11-10 云托管 133 版本因 backend/target/classes 残留 V1__init.sql
 * （源码与 git 均无此文件，属旧构建遗留）被打进 jar，Flyway 直接报
 * "Found more than one migration with version 1" 拒绝启动；打包时补 mvn clean 即可。
 */
class MigrationIntegrityTest {

    private static final Pattern VERSION = Pattern.compile("^V([0-9][0-9._]*)__.*\\.sql$");

    @Test
    void migrationVersionsAreUniqueOnClasspath() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");

        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name == null) {
                continue;
            }
            Matcher matcher = VERSION.matcher(name);
            if (matcher.matches()) {
                byVersion.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(name);
            }
        }

        List<String> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .sorted()
                .toList();
        assertTrue(duplicates.isEmpty(),
                "classpath 下存在重复版本的迁移脚本（多为 target/classes 残留，打包前需 mvn clean）: " + duplicates);
        assertTrue(byVersion.size() >= 22, "迁移脚本数量异常，classpath 扫描可能失效: " + byVersion.size());
    }

    @Test
    void targetedPerformanceIndexesAreAdditiveAndSafe() throws Exception {
        ClassPathResource migration = new ClassPathResource("db/migration/V64__targeted_performance_indexes.sql");
        assertTrue(migration.exists(), "缺少 V64 定向性能索引迁移");
        String sql = new String(migration.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (String index : List.of("idx_mr_prev_version", "idx_mr_order_version", "idx_mr_created_at",
                "idx_mr_sign_queue", "idx_wo_plan_time", "idx_wo_elevator_status_checkout",
                "idx_fault_created_at", "idx_fault_unit_created", "idx_fault_escalation",
                "idx_reg_orig_created", "idx_elevator_unit")) {
            assertTrue(sql.contains(index), "V64 缺少索引: " + index);
        }
        assertTrue(!sql.matches("(?is).*\\b(UNIQUE|PARTITION|UPDATE|DELETE)\\b.*"),
                "V64 只能做性能索引，不得包含唯一约束或数据清洗");
        assertTrue(!sql.matches("(?is).*\\bDROP\\s+TABLE\\b.*"), "V64 不得删表");
    }
}
