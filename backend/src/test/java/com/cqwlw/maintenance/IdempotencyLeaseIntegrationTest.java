package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "IDEMPOTENCY_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class IdempotencyLeaseIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static DataSourceTransactionManager transactionManager;
    private static SqlSessionTemplate sqlSession;

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
        databaseName = "idempotency_lease_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            throw new IllegalStateException("临时幂等租约测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void activeHeartbeatPreventsStaleTakeover() throws Exception {
        IdempotencyService service = service(sqlSession.getMapper(IdempotencyKeyMapper.class));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondCalls = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> first = pool.submit(() -> service.executeExternal(
                    "lease-active", Map.of("id", 1), request(), () -> {
                        started.countDown();
                        await(release);
                        return Map.of("owner", "A");
                    }));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            markStale("lease-active");

            service.heartbeatActive();

            assertThrows(BizException.class, () -> service.executeExternal(
                    "lease-active", Map.of("id", 1), request(), () -> {
                        secondCalls.incrementAndGet();
                        return Map.of("owner", "B");
                    }));
            assertEquals(0, secondCalls.get());
            release.countDown();
            assertEquals("A", first.get(10, TimeUnit.SECONDS).get("owner"));
            assertTrue(response("lease-active").contains("\"owner\":\"A\""));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleOwnerTakeoverRejectsOldCompletion() throws Exception {
        IdempotencyService firstService = service(sqlSession.getMapper(IdempotencyKeyMapper.class));
        IdempotencyService secondService = service(sqlSession.getMapper(IdempotencyKeyMapper.class));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> first = pool.submit(() -> firstService.executeExternal(
                    "lease-stale", Map.of("id", 1), request(), () -> {
                        started.countDown();
                        await(release);
                        return Map.of("owner", "A");
                    }));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            markStale("lease-stale");

            Map<String, Object> second = secondService.executeExternal(
                    "lease-stale", Map.of("id", 1), request(), () -> Map.of("owner", "B"));

            assertEquals("B", second.get("owner"));
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(response("lease-stale").contains("\"owner\":\"B\""));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lateOldOwnerRegistrationDoesNotReplaceNewOwnerHeartbeat() throws Exception {
        CountDownLatch firstCommitted = new CountDownLatch(1);
        CountDownLatch resumeFirst = new CountDownLatch(1);
        PlatformTransactionManager blocking = blockingAfterFirstCommit(firstCommitted, resumeFirst);
        IdempotencyService service = new IdempotencyService(
                sqlSession.getMapper(IdempotencyKeyMapper.class), new TransactionTemplate(blocking));
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> service.executeExternal(
                    "lease-late-register", Map.of("id", 1), request(), () -> Map.of("owner", "A")));
            assertTrue(firstCommitted.await(10, TimeUnit.SECONDS));
            markStale("lease-late-register");
            Future<Map<String, Object>> second = pool.submit(() -> service.executeExternal(
                    "lease-late-register", Map.of("id", 1), request(), () -> {
                        secondStarted.countDown();
                        await(releaseSecond);
                        return Map.of("owner", "B");
                    }));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));

            resumeFirst.countDown();
            ExecutionException oldFailure = assertThrows(ExecutionException.class,
                    () -> first.get(10, TimeUnit.SECONDS));
            assertTrue(oldFailure.getCause() instanceof IllegalStateException);
            markStale("lease-late-register");
            service.heartbeatActive();

            assertTrue(heartbeatAt("lease-late-register")
                    .isAfter(com.cqwlw.maintenance.util.TimeUtil.now().minusMinutes(1)));
            releaseSecond.countDown();
            assertEquals("B", second.get(10, TimeUnit.SECONDS).get("owner"));
        } finally {
            resumeFirst.countDown();
            releaseSecond.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void externalCompletionFailureDoesNotReleaseKey() throws Exception {
        IdempotencyKeyMapper delegate = sqlSession.getMapper(IdempotencyKeyMapper.class);
        IdempotencyKeyMapper failing = (IdempotencyKeyMapper) Proxy.newProxyInstance(
                IdempotencyKeyMapper.class.getClassLoader(), new Class<?>[]{IdempotencyKeyMapper.class},
                (proxy, method, args) -> {
                    if ("complete".equals(method.getName())) {
                        throw new IllegalStateException("注入完成失败");
                    }
                    return invoke(delegate, method, args);
                });
        IdempotencyService service = service(failing);

        assertThrows(IllegalStateException.class, () -> service.executeExternal(
                "lease-complete-failure", Map.of("id", 1), request(), () -> Map.of("ok", true)));

        assertEquals(1L, count("lease-complete-failure"));
        assertEquals(null, response("lease-complete-failure"));
        assertThrows(BizException.class, () -> service.executeExternal(
                "lease-complete-failure", Map.of("id", 1), request(), () -> Map.of("ok", false)));
    }

    @Test
    void legacyRecordNeitherReplaysNorExecutes() throws Exception {
        insertLegacy("lease-legacy", "{\"ok\":true}");
        AtomicInteger calls = new AtomicInteger();
        IdempotencyService service = service(sqlSession.getMapper(IdempotencyKeyMapper.class));

        assertThrows(BizException.class, () -> service.executeExternal(
                "lease-legacy", Map.of(), request(), () -> {
                    calls.incrementAndGet();
                    return Map.of("ok", false);
                }));

        assertEquals(0, calls.get());
        assertEquals("{\"ok\":true}", response("lease-legacy"));
    }

    private static PlatformTransactionManager blockingAfterFirstCommit(CountDownLatch committed,
                                                                        CountDownLatch resume) {
        AtomicInteger commits = new AtomicInteger();
        return (PlatformTransactionManager) Proxy.newProxyInstance(
                PlatformTransactionManager.class.getClassLoader(),
                new Class<?>[]{PlatformTransactionManager.class}, (proxy, method, args) -> {
                    Object result = invoke(transactionManager, method, args);
                    if ("commit".equals(method.getName()) && commits.incrementAndGet() == 1) {
                        committed.countDown();
                        await(resume);
                    }
                    return result;
                });
    }

    private static LocalDateTime heartbeatAt(String key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT heartbeat_at FROM idempotency_key WHERE idem_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getTimestamp(1).toLocalDateTime();
            }
        }
    }

    private static IdempotencyService service(IdempotencyKeyMapper mapper) {
        return new IdempotencyService(mapper, new TransactionTemplate(transactionManager));
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/external-lease");
        when(request.getParameterMap()).thenReturn(Map.of());
        when(request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE)).thenReturn("EMPLOYEE");
        when(request.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn("emp_lease");
        return request;
    }

    private static void markStale(String key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE idempotency_key SET heartbeat_at = ? WHERE idem_key = ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(
                    com.cqwlw.maintenance.util.TimeUtil.now().minusMinutes(20)));
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private static void insertLegacy(String key, String response) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO idempotency_key (idem_key, path, response_json, created_at) "
                             + "VALUES (?, '/legacy', ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, response);
            statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
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

    private static long count(String key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM idempotency_key WHERE idem_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待测试释放超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
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
}
