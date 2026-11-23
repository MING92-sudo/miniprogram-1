package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.RecordLifecycleService;
import com.cqwlw.maintenance.service.RecordPdfService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 拒签链路事务回滚：记录退回与工单重开同成同败（故障注入只打第二写）。复用档案 IT 的 MySQL 门控。 */
@EnabledIfEnvironmentVariable(named = "ARCHIVE_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class RecordLifecycleTransactionIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("ARCHIVE_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("ARCHIVE_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("ARCHIVE_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        String authority = serverPath.substring("jdbc:mysql://".length());
        if (!serverPath.endsWith("/") || authority.indexOf('/') != authority.length() - 1) {
            throw new IllegalArgumentException("ARCHIVE_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "refuse_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            configuration.addMapper(MaintainRecordMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
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
            throw new IllegalStateException("临时拒签事务测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void refuseRollsBackRecordWhenReopenUpdateThrows() throws Exception {
        seedOrderAndRecord("wo_it_throw", "ur_it_throw", "DONE");
        RecordLifecycleService service = transactionalService(service(
                sqlSession.getMapper(MaintainRecordMapper.class),
                failingMapper(WorkOrderMapper.class, "update", 1)));

        assertThrows(IllegalStateException.class,
                () -> service.refuseByToken("ur_it_throw", "sg-it-throw", "R2", null));

        assertEquals("PENDING_CLIENT_SIGNATURE", scalar(
                "SELECT lifecycle_status FROM maintain_record WHERE id = ?", "ur_it_throw"));
        assertEquals("PENDING", scalar(
                "SELECT confirm_status FROM maintain_record WHERE id = ?", "ur_it_throw"));
        assertNull(scalar("SELECT refuse_reason FROM maintain_record WHERE id = ?", "ur_it_throw"));
        assertEquals("DONE", scalar("SELECT status FROM work_order WHERE id = ?", "wo_it_throw"));
        assertEquals("file_w1", scalar(
                "SELECT worker_signature_file_id FROM work_order WHERE id = ?", "wo_it_throw"));
    }

    @Test
    void refuseRollsBackRecordWhenOrderNotDone() throws Exception {
        // 业务守门：工单非 DONE 时重开 0 行 → BizException，记录更新必须随同事务回滚
        seedOrderAndRecord("wo_it_guard", "ur_it_guard", "PROCESSING");
        RecordLifecycleService service = transactionalService(service(
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(WorkOrderMapper.class)));

        assertThrows(BizException.class,
                () -> service.refuseByToken("ur_it_guard", "sg-it-guard", "R2", null));

        assertEquals("PENDING_CLIENT_SIGNATURE", scalar(
                "SELECT lifecycle_status FROM maintain_record WHERE id = ?", "ur_it_guard"));
        assertEquals("PENDING", scalar(
                "SELECT confirm_status FROM maintain_record WHERE id = ?", "ur_it_guard"));
    }

    @Test
    void refuseWithoutTransactionLeavesPartialState() throws Exception {
        // 检测能力证人：同一故障注入打到裸实例（无事务代理）时记录半成品可见——证明上面的回滚断言不是空转
        seedOrderAndRecord("wo_it_plain", "ur_it_plain", "DONE");
        RecordLifecycleService plain = service(
                sqlSession.getMapper(MaintainRecordMapper.class),
                failingMapper(WorkOrderMapper.class, "update", 1));

        assertThrows(IllegalStateException.class,
                () -> plain.refuseByToken("ur_it_plain", "sg-it-plain", "R2", null));

        assertEquals("REFUSED_RETURNED", scalar(
                "SELECT lifecycle_status FROM maintain_record WHERE id = ?", "ur_it_plain"));
        assertEquals("DONE", scalar("SELECT status FROM work_order WHERE id = ?", "wo_it_plain"));
    }

    @Test
    void refuseSucceedsRollsForward() throws Exception {
        seedOrderAndRecord("wo_it_ok", "ur_it_ok", "DONE");
        RecordLifecycleService service = transactionalService(service(
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(WorkOrderMapper.class)));

        Map<String, Object> result = service.refuseByToken("ur_it_ok", "sg-it-ok", "R2", " 漏项 ");

        assertEquals(true, result.get("ok"));
        assertEquals("REFUSED_RETURNED", scalar(
                "SELECT lifecycle_status FROM maintain_record WHERE id = ?", "ur_it_ok"));
        assertEquals("R2", scalar("SELECT refuse_code FROM maintain_record WHERE id = ?", "ur_it_ok"));
        assertEquals("PROCESSING", scalar("SELECT status FROM work_order WHERE id = ?", "wo_it_ok"));
        assertNull(scalar("SELECT worker_signature_file_id FROM work_order WHERE id = ?", "wo_it_ok"));
    }

    private static void seedOrderAndRecord(String orderId, String recordId, String orderStatus) throws Exception {
        execute("INSERT INTO work_order (id, order_no, elevator_id, work_type, work_type_code, plan_time, status,"
                        + " worker_signature_file_id, worker_signature_at,"
                        + " assistant_signature_file_id, assistant_signature_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, "NO-" + orderId, "el_it", "半月维保", "HM", testTime(), orderStatus,
                "file_w1", testTime(), "file_a1", testTime());
        execute("INSERT INTO maintain_record (id, lifecycle_status, confirm_status, share_token,"
                        + " work_order_id, original_record_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                recordId, "PENDING_CLIENT_SIGNATURE", "PENDING", "sg-it-" + orderId.substring(6),
                orderId, "OID-" + recordId, testTime());
    }

    private static RecordLifecycleService service(MaintainRecordMapper records, WorkOrderMapper orders) {
        return new RecordLifecycleService(records, orders, mock(EmployeeMapper.class),
                mock(ElevatorMapper.class), mock(UseUnitMapper.class), mock(RecordPdfService.class),
                mock(FileStorageService.class), mock(SysConfigMapper.class));
    }

    private static RecordLifecycleService transactionalService(RecordLifecycleService service) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (RecordLifecycleService) factory.getProxy();
    }

    private static <T> T failingMapper(Class<T> type, String methodName, int failureCall) {
        T delegate = sqlSession.getMapper(type);
        AtomicInteger calls = new AtomicInteger();
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (methodName.equals(method.getName()) && calls.incrementAndGet() == failureCall) {
                        throw new IllegalStateException("injected " + methodName + " failure");
                    }
                    return invoke(delegate, method, args);
                }));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Timestamp testTime() {
        return Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private static void execute(String sql, Object... params) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object scalar(String sql, Object... params) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1);
            }
        }
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl + database
                : baseUrl.substring(0, query) + database + baseUrl.substring(query);
    }
}
