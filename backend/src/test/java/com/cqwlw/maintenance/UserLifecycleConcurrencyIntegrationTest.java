package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.UserLifecycleService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "LIFECYCLE_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class UserLifecycleConcurrencyIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("LIFECYCLE_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("LIFECYCLE_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("LIFECYCLE_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        String authority = serverPath.substring("jdbc:mysql://".length());
        if (!serverPath.endsWith("/") || authority.indexOf('/') != authority.length() - 1) {
            throw new IllegalArgumentException("LIFECYCLE_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "lifecycle_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            configuration.addMapper(EmployeeMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
            configuration.addMapper(FaultMapper.class);
            factory.setConfiguration(configuration);
            sqlSession = new SqlSessionTemplate(factory.getObject());
            transactionManager = new DataSourceTransactionManager(dataSource);
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
            throw new IllegalStateException("临时离职并发测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void oppositeResignationsDoNotDeadlockOrPartiallyMigrate() throws Exception {
        insertEmployee("emp_a", "pid_a");
        insertEmployee("emp_b", "pid_b");
        AtomicInteger lockCalls = new AtomicInteger();
        CountDownLatch bothFirstLocks = new CountDownLatch(1);
        EmployeeMapper employeeMapper = (EmployeeMapper) Proxy.newProxyInstance(
                EmployeeMapper.class.getClassLoader(), new Class<?>[]{EmployeeMapper.class},
                (proxy, method, args) -> {
                    Object result = invoke(sqlSession.getMapper(EmployeeMapper.class), method, args);
                    if ("selectList".equals(method.getName())) {
                        if (lockCalls.incrementAndGet() == 2) {
                            bothFirstLocks.countDown();
                        }
                        bothFirstLocks.await(200, TimeUnit.MILLISECONDS);
                    }
                    return result;
                });
        UserLifecycleService service = new UserLifecycleService(employeeMapper,
                sqlSession.getMapper(WorkOrderMapper.class), sqlSession.getMapper(FaultMapper.class),
                mock(WorkOrderService.class), new TransactionTemplate(transactionManager));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(
                    () -> service.resign("emp_admin", "emp_a", "emp_b"));
            Future<Map<String, Object>> second = pool.submit(
                    () -> service.resign("emp_admin", "emp_b", "emp_a"));

            Outcome firstOutcome = outcome(first);
            Outcome secondOutcome = outcome(second);

            assertTrue(firstOutcome.succeeded() || secondOutcome.succeeded(), "双向离职应有序列化胜者");
            assertTrue(firstOutcome.succeeded() != secondOutcome.succeeded(),
                    "另一方应因接手人已停用而拒绝，不应数据库死锁");
            assertTrue(firstOutcome.businessRejected() || secondOutcome.businessRejected(),
                    "败方须为既有业务守卫 422，而非死锁/其他运行时异常");
            assertEquals(1L, countEnabled());
            assertEquals(0L, countRows("work_order"));
            assertEquals(0L, countRows("fault"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome outcome(Future<Map<String, Object>> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return new Outcome(true, false);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BizException biz && biz.getCode() == 422) {
                return new Outcome(false, true);
            }
            return fail("双向离职出现非业务异常/死锁", e);
        }
    }

    private static void insertEmployee(String id, String platformId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO sys_employee (id, name, phone, account, password_hash, role, role_text, "
                             + "platform_id, region_id, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, id);
            statement.setString(2, "员工" + id);
            statement.setString(3, "1390000" + (id.endsWith("a") ? "0001" : "0002"));
            statement.setString(4, id);
            statement.setString(5, "not-used");
            statement.setString(6, "WORKER");
            statement.setString(7, "维保员");
            statement.setString(8, platformId);
            statement.setString(9, "reg_1");
            statement.setBoolean(10, true);
            statement.executeUpdate();
        }
    }

    private static long countEnabled() throws Exception {
        return countRows("sys_employee");
    }

    private static long countRows(String table) throws Exception {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("非法测试表名");
        }
        String sql = table.equals("sys_employee")
                ? "SELECT COUNT(*) FROM sys_employee WHERE enabled = 1"
                : "SELECT COUNT(*) FROM " + table;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
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

    private record Outcome(boolean succeeded, boolean businessRejected) {
    }
}
