package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuthController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用上下文 + Flyway 集成测试（未提供 MySQL 时自动跳过）：
 * ① 全量 Spring 上下文可加载（含 WxAuthService 注入、AppProperties 绑定）；
 * ② Flyway 在真实库上按序执行 V1—V6，且 V6 记录 success=1、唯一索引 uk_openid 建立；
 * ③ AuthController 的 {@code @Transactional} 代理生效（绑定"解绑 + 写入"同成同败）。
 *
 * <p>运行：设置 {@code P0_TEST_MYSQL_URL/USER/PASSWORD}（建议指向空库，Flyway 会建表）后 {@code mvn test}。
 * 云端为 MySQL 5.7，本测试对版本差异不作断言，只验证迁移脚本本身可被 Flyway 执行。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.seed-demo-data=false"
})
@EnabledIfEnvironmentVariable(named = "P0_TEST_MYSQL_URL", matches = ".+")
class ApplicationBootDbTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("P0_TEST_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("P0_TEST_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("P0_TEST_MYSQL_PASSWORD"));
        registry.add("app.jwt-secret", () -> "unit-test-secret-key-please-change-0123456789");
        registry.add("app.wx-appid", () -> "wxappid123");
        registry.add("app.wx-appsecret", () -> "wxsecret456");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthController authController;

    @Test
    void contextLoadsFlywayAppliesV6AndTransactionalProxyIsActive() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = 1", Integer.class);
        assertEquals(1, applied, "V6（串号清理 + openid 唯一约束）必须在真实库执行成功");

        Integer uniqueIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE() AND table_name = 'sys_employee'"
                        + " AND index_name = 'uk_openid' AND non_unique = 0", Integer.class);
        assertEquals(1, uniqueIndex, "uk_openid 必须存在且唯一");

        assertTrue(AopUtils.isAopProxy(authController), "@Transactional 代理未生效，解绑+绑定无法同成同败");
    }
}
