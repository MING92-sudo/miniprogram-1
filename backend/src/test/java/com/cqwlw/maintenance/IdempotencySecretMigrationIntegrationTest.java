package com.cqwlw.maintenance;

import com.cqwlw.maintenance.util.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "IDEMPOTENCY_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class IdempotencySecretMigrationIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("IDEMPOTENCY_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("IDEMPOTENCY_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("IDEMPOTENCY_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        String authority = serverPath.substring("jdbc:mysql://".length());
        if (!serverPath.endsWith("/") || authority.indexOf('/') != authority.length() - 1) {
            throw new IllegalArgumentException("IDEMPOTENCY_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "idempotency_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + databaseName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        try {
            dataSource = new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(),
                    databaseUrl(serverUrl, databaseName), username, password);
            Flyway.configure().dataSource(dataSource).baselineOnMigrate(true)
                    .target("68").load().migrate();
        } catch (Exception e) {
            dropDisposableDatabase();
            throw e;
        }
    }

    @AfterAll
    static void dropDisposableDatabase() {
        if (serverUrl == null || databaseName == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + databaseName + "`");
        } catch (Exception e) {
            throw new IllegalStateException("临时幂等迁移测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void v69RedactsExistingSecretsSafely() throws Exception {
        insert("valid-root", "{\"id\":\"emp_1\",\"initialPassword\":\"root-secret\","
                + "\"password\":\"plain-secret\",\"nested\":{\"label\":\"keep\"}}");
        insert("valid-nested", "{\"id\":\"nested\",\"nested\":{\"password\":\"nested-secret\"},"
                + "\"items\":[{\"initialPassword\":\"array-secret\"}]}");
        insert("invalid-secret", "{\"id\":\"broken\",\"initialPassword\":\"broken-secret\"");
        insert("plain", "{\"id\":\"plain\",\"ok\":true}");
        insert("invalid-plain", "{\"id\":\"broken-plain\"");
        String escapedPasswordKey = "pass" + "\\" + "u0077" + "ord";
        insert("valid-escaped-key", "{\"id\":\"escaped\",\"nested\":{\"" + escapedPasswordKey
                + "\":\"escaped-secret\"}}");
        insert("invalid-escaped-secret", "{\"id\":\"broken-escaped\",\"" + escapedPasswordKey
                + "\":\"broken-secret\"");
        insert("value-only", "{\"note\":\"password\",\"other\":\"initialPassword\"}");

        Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).load().migrate();

        Map<String, Object> validRoot = JsonUtil.readMap(response("valid-root"));
        assertEquals("emp_1", validRoot.get("id"));
        assertEquals("keep", ((Map<?, ?>) validRoot.get("nested")).get("label"));
        assertFalse(validRoot.containsKey("initialPassword"));
        assertFalse(validRoot.containsKey("password"));
        Map<String, Object> validNested = JsonUtil.readMap(response("valid-nested"));
        assertEquals("nested", validNested.get("id"));
        assertFalse(((Map<?, ?>) validNested.get("nested")).containsKey("password"));
        Map<?, ?> nestedItem = (Map<?, ?>) ((List<?>) validNested.get("items")).get(0);
        assertFalse(nestedItem.containsKey("initialPassword"));
        assertEquals("{\"redacted\":true}", response("invalid-secret"));
        assertEquals("{\"id\":\"plain\",\"ok\":true}", response("plain"));
        assertEquals("{\"id\":\"broken-plain\"", response("invalid-plain"));
        assertEquals("{\"note\":\"password\",\"other\":\"initialPassword\"}", response("value-only"));
        Map<String, Object> escaped = JsonUtil.readMap(response("valid-escaped-key"));
        assertEquals("escaped", escaped.get("id"));
        assertFalse(((Map<?, ?>) escaped.get("nested")).containsKey("password"));
        assertEquals("{\"redacted\":true}", response("invalid-escaped-secret"));
        assertEquals(8L, countKeys());
    }

    private static void insert(String key, String responseJson) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO idempotency_key (idem_key, path, response_json, created_at) "
                             + "VALUES (?, '/test', ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, responseJson);
            statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)));
            statement.executeUpdate();
        }
    }

    private static String response(String key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT response_json FROM idempotency_key WHERE idem_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static long countKeys() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM idempotency_key")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl + database
                : baseUrl.substring(0, query) + database + baseUrl.substring(query);
    }
}
