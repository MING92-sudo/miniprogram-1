package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "IDEMPOTENCY_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class IdempotencyDbAtomicityIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;
    private static DataSourceTransactionManager transactionManager;

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
        databaseName = "idempotency_db_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + databaseName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        try {
            dataSource = new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(),
                    databaseUrl(serverUrl, databaseName), username, password);
            Flyway.configure().dataSource(dataSource).baselineOnMigrate(true).load().migrate();
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(IdempotencyKeyMapper.class);
            configuration.addMapper(UseUnitMapper.class);
            factory.setConfiguration(configuration);
            sqlSession = new SqlSessionTemplate(factory.getObject());
            transactionManager = new DataSourceTransactionManager(dataSource);
        } catch (Exception e) {
            dropDisposableDatabase();
            throw e;
        }
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM use_unit");
            statement.executeUpdate("DELETE FROM idempotency_key");
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
            throw new IllegalStateException("临时幂等事务测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void dbBusinessAndCompletionCommitTogether() throws Exception {
        execute(service(CompletionMode.NORMAL), "db-success", "uu_db_success", false);

        assertRowCounts("uu_db_success", "db-success", 1, 1);
    }

    @Test
    void dbCompletionFailureRollsBackBusinessAndReservation() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> execute(service(CompletionMode.THROW), "db-complete-error", "uu_db_error", false));

        assertRowCounts("uu_db_error", "db-complete-error", 0, 0);
    }

    @Test
    void dbCompletionZeroRowsRollsBackBusiness() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> execute(service(CompletionMode.ZERO), "db-complete-zero", "uu_db_zero", false));

        assertRowCounts("uu_db_zero", "db-complete-zero", 0, 0);
    }

    @Test
    void dbBusinessFailureRollsBackReservationAndBusiness() throws Exception {
        assertThrows(BizException.class,
                () -> execute(service(CompletionMode.NORMAL), "db-business-error", "uu_db_business", true));

        assertRowCounts("uu_db_business", "db-business-error", 0, 0);
    }

    private static void execute(IdempotencyService service, String key, String unitId, boolean businessFails) {
        service.executeDb(key, Map.of("id", unitId), request(), () -> {
            UseUnit unit = new UseUnit();
            unit.id = unitId;
            unit.unitName = "幂等事务测试";
            sqlSession.getMapper(UseUnitMapper.class).insert(unit);
            if (businessFails) {
                throw new BizException(422, "注入业务失败");
            }
            return Map.of("ok", true, "id", unitId);
        });
    }

    private static IdempotencyService service(CompletionMode mode) {
        IdempotencyKeyMapper delegate = sqlSession.getMapper(IdempotencyKeyMapper.class);
        IdempotencyKeyMapper mapper = (IdempotencyKeyMapper) Proxy.newProxyInstance(
                IdempotencyKeyMapper.class.getClassLoader(), new Class<?>[]{IdempotencyKeyMapper.class},
                (proxy, method, args) -> {
                    if ("complete".equals(method.getName())) {
                        if (mode == CompletionMode.THROW) {
                            throw new IllegalStateException("注入幂等完成失败");
                        }
                        if (mode == CompletionMode.ZERO) {
                            return 0;
                        }
                    }
                    return invoke(delegate, method, args);
                });
        return new IdempotencyService(mapper, new TransactionTemplate(transactionManager));
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/db-atomicity");
        when(request.getParameterMap()).thenReturn(Map.of());
        when(request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE)).thenReturn("EMPLOYEE");
        when(request.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn("emp_db_test");
        return request;
    }

    private static void assertRowCounts(String unitId, String key, long units, long completedKeys) throws Exception {
        assertEquals(units, count("SELECT COUNT(*) FROM use_unit WHERE id = ?", unitId));
        assertEquals(completedKeys, count(
                "SELECT COUNT(*) FROM idempotency_key WHERE idem_key = ? AND response_json IS NOT NULL", key));
        if (completedKeys == 0) {
            assertEquals(0L, count("SELECT COUNT(*) FROM idempotency_key WHERE idem_key = ?", key));
        }
    }

    private static long count(String sql, String param) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, param);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl + database
                : baseUrl.substring(0, query) + database + baseUrl.substring(query);
    }

    private enum CompletionMode {
        NORMAL, THROW, ZERO
    }
}
