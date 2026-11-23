package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DispatchService;
import com.cqwlw.maintenance.service.MigrationIssueService;
import com.cqwlw.maintenance.service.OrderNoIssuer;
import com.cqwlw.maintenance.service.TenantSnapshotService;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DISPATCH_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class DispatchConcurrencyIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("DISPATCH_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("DISPATCH_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("DISPATCH_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        if (!serverPath.endsWith("/")) {
            throw new IllegalArgumentException("DISPATCH_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "dispatch_it_" + UUID.randomUUID().toString().replace("-", "");
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
            configuration.addMapper(ElevatorMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
            configuration.addMapper(MessageMapper.class);
            factory.setConfiguration(configuration);
            sqlSession = new SqlSessionTemplate(factory.getObject());
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
            throw new IllegalStateException("临时派单测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void concurrentManualAndScheduledDispatchCreatesOneOrder() throws Exception {
        ElevatorMapper elevators = synchronizedElevatorMapper();
        WorkOrderMapper orders = synchronizedOrderMapper();
        ChecklistService checklists = new ChecklistService();
        checklists.load();
        TenantSnapshotService snapshots = new TenantSnapshotService(mock(UseUnitMapper.class),
                mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        DispatchService service = new DispatchService(elevators, orders,
                sqlSession.getMapper(MessageMapper.class), mock(EmployeeMapper.class),
                checklists, new OrderNoIssuer(orders), snapshots);
        insertDueElevator();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WorkOrder> manual = pool.submit(() -> {
                path.set("manual");
                return transactions.execute(status -> service.dispatchNow("el_race"));
            });
            assertTrue(manualActiveChecked.await(10, TimeUnit.SECONDS),
                    "手动派单未完成第一次在途检查");
            Future<List<WorkOrder>> scheduled = pool.submit(() -> {
                path.set("scheduled");
                return transactions.execute(status -> service.ensureDueOrders());
            });

            WorkOrder manualOrder = null;
            BizException manualRejection = null;
            Throwable unexpectedFailure = null;
            List<WorkOrder> scheduledOrders = null;
            try {
                manualOrder = manual.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof BizException bizException) {
                    manualRejection = bizException;
                } else {
                    unexpectedFailure = e.getCause();
                }
            }
            try {
                scheduledOrders = scheduled.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                unexpectedFailure = e.getCause();
            }

            assertNull(unexpectedFailure, "并发派单只能正常建单或以既有业务守卫拒绝");
            if (manualRejection != null) {
                assertEquals(422, manualRejection.getCode());
            }
            int accepted = (manualOrder == null ? 0 : 1) + (scheduledOrders == null ? 0 : scheduledOrders.size());
            assertEquals(1, accepted, () -> "手动与定时派单必须恰好一个入口建单，events=" + events);
            assertEquals(1, countActiveOrders(), "同一电梯最多一张非 DONE 工单");
        } finally {
            pool.shutdownNow();
        }
    }

    private static final ThreadLocal<String> path = new ThreadLocal<>();
    private static final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean manualLocked = new AtomicBoolean();
    private static final AtomicInteger scheduledActiveChecks = new AtomicInteger();
    private static final CountDownLatch manualActiveChecked = new CountDownLatch(1);
    private static final CountDownLatch scheduledLockedActiveCheck = new CountDownLatch(1);

    private ElevatorMapper synchronizedElevatorMapper() {
        ElevatorMapper delegate = sqlSession.getMapper(ElevatorMapper.class);
        return (ElevatorMapper) Proxy.newProxyInstance(ElevatorMapper.class.getClassLoader(),
                new Class<?>[]{ElevatorMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("selectById".equals(method.getName()) || "selectOne".equals(method.getName())) {
                        events.add(path.get() + " " + method.getName());
                    }
                    if ("manual".equals(path.get()) && "selectOne".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof LambdaQueryWrapper<?> query
                            && query.getSqlSegment().toUpperCase().contains("FOR UPDATE")) {
                        manualLocked.set(true);
                    }
                    return result;
                });
    }

    private WorkOrderMapper synchronizedOrderMapper() {
        WorkOrderMapper delegate = sqlSession.getMapper(WorkOrderMapper.class);
        return (WorkOrderMapper) Proxy.newProxyInstance(WorkOrderMapper.class.getClassLoader(),
                new Class<?>[]{WorkOrderMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("insert".equals(method.getName())) {
                        events.add(path.get() + " insert");
                    }
                    if (!"selectCount".equals(method.getName()) || !isActiveCheck(args)) {
                        return result;
                    }
                    events.add(path.get() + " active=" + result);
                    if ("manual".equals(path.get())) {
                        manualActiveChecked.countDown();
                        if (!manualLocked.get() && !scheduledLockedActiveCheck.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("定时派单未完成锁内在途检查");
                        }
                    } else if ("scheduled".equals(path.get())
                            && scheduledActiveChecks.incrementAndGet() == 2) {
                        scheduledLockedActiveCheck.countDown();
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static boolean isActiveCheck(Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof LambdaQueryWrapper<?> query)) {
            return false;
        }
        String sql = query.getSqlSegment();
        return sql.contains("elevator_id") && sql.contains("status");
    }

    private static void insertDueElevator() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO elevator (id, elevator_code, elevator_name, category, region_id, "
                             + "work_type_code, interval_days, status, last_maintenance_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, "el_race");
            statement.setString(2, "EM-RACE");
            statement.setString(3, "并发测试电梯");
            statement.setString(4, "曳引与强制驱动电梯");
            statement.setString(5, "rg_race");
            statement.setString(6, "HM");
            statement.setInt(7, 15);
            statement.setString(8, "ACTIVE");
            statement.setTimestamp(9, Timestamp.valueOf(LocalDateTime.now().minusDays(16)));
            statement.executeUpdate();
        }
    }

    private static long countActiveOrders() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM work_order WHERE elevator_id = ? AND status <> 'DONE'")) {
            statement.setString(1, "el_race");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl + database
                : baseUrl.substring(0, query) + database + baseUrl.substring(query);
    }
}
