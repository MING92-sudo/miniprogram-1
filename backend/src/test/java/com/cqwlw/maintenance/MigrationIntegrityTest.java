package com.cqwlw.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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
}
