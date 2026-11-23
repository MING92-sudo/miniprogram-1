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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private static DataSourceTransactionManager transactionManager;
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
        databaseName = "dispatch_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            transactionManager = new DataSourceTransactionManager(dataSource);
            transactions = new TransactionTemplate(transactionManager);
        } catch (Exception e) {
            dropDisposableDatabase();
            throw e;
        }
    }

    @BeforeEach
    void cleanDomainRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM message");
            statement.executeUpdate("DELETE FROM work_order");
            statement.executeUpdate("DELETE FROM elevator");
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
        insertElevator("el_race", LocalDateTime.now().minusDays(16));

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

    @Test
    void scheduledDispatchReadsMaintenanceCommittedBeforeLockAcquisition() throws Exception {
        insertElevator("el_stale_due", LocalDateTime.now().minusDays(16));

        List<WorkOrder> created = dispatchAfterMaintenanceCommit(
                "el_stale_due", "HM", LocalDateTime.now());

        assertTrue(created.isEmpty(), "锁内必须读取等待期间提交的最近维保，不得按锁前旧到期日派单");
    }

    @Test
    void scheduledDispatchReadsPeriodBaselinesCommittedBeforeLockAcquisition() throws Exception {
        insertElevator("el_stale_type", LocalDateTime.now().minusDays(400));

        List<WorkOrder> created = dispatchAfterMaintenanceCommit(
                "el_stale_type", "OY", LocalDateTime.now().minusDays(16));

        assertEquals(1, created.size());
        assertEquals("HM", created.get(0).workTypeCode,
                "首次维保与年保最近维保均提交于等锁期间，不得按锁前 400 天基线误升年保");
    }

    @Test
    void deleteAfterInflightCheckCannotDeleteElevatorUnderConcurrentDispatch() throws Exception {
        insertElevator("el_delete_race", LocalDateTime.now().minusDays(16));
        pauseDeleteAfterInflight = true;
        com.cqwlw.maintenance.service.AdminArchiveService deleteService = newDeleteService(
                elevatorForDelete(), pausingOrderMapper());
        DispatchService dispatchService = newDispatchService(dispatchElevatorMapper(),
                sqlSession.getMapper(WorkOrderMapper.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> deletion = pool.submit(() -> deleteService.deleteElevator("el_delete_race"));
            assertTrue(deleteInflightChecked.await(10, TimeUnit.SECONDS), "删除方未完成在途检查");
            Future<WorkOrder> dispatch = pool.submit(() -> transactions.execute(
                    status -> dispatchService.dispatchNow("el_delete_race")));
            assertTrue(dispatchRead.await(10, TimeUnit.SECONDS), "派单方未读取电梯");
            allowDelete.countDown();

            assertEquals(true, deletion.get(30, TimeUnit.SECONDS).get("ok"));
            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> dispatch.get(30, TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof BizException);
            assertEquals(1404, ((BizException) rejection.getCause()).getCode());
            assertEquals(0L, countActiveOrders("el_delete_race"));
            assertEquals(0L, countElevator("el_delete_race"));
        } finally {
            allowDelete.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void deleteWaitsForDispatchAndRejectsInflightElevator() throws Exception {
        insertElevator("el_dispatch_first", LocalDateTime.now().minusDays(16));
        pauseDispatchAfterLock = true;
        DispatchService dispatchService = newDispatchService(dispatchElevatorMapper(),
                sqlSession.getMapper(WorkOrderMapper.class));
        com.cqwlw.maintenance.service.AdminArchiveService deleteService = newDeleteService(
                elevatorForDelete(), sqlSession.getMapper(WorkOrderMapper.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WorkOrder> dispatch = pool.submit(() -> transactions.execute(
                    status -> dispatchService.dispatchNow("el_dispatch_first")));
            assertTrue(dispatchLocked.await(10, TimeUnit.SECONDS), "派单方未持有电梯锁");
            Future<Map<String, Object>> deletion = pool.submit(
                    () -> deleteService.deleteElevator("el_dispatch_first"));
            assertTrue(deleteRead.await(10, TimeUnit.SECONDS), "删除方未尝试读取电梯");
            allowDispatch.countDown();

            assertTrue(dispatch.get(30, TimeUnit.SECONDS) != null);
            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> deletion.get(30, TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof BizException);
            assertEquals(422, ((BizException) rejection.getCause()).getCode());
            assertEquals(1L, countActiveOrders("el_dispatch_first"));
            assertEquals(1L, countElevator("el_dispatch_first"));
        } finally {
            allowDispatch.countDown();
            pool.shutdownNow();
        }
    }

    private List<WorkOrder> dispatchAfterMaintenanceCommit(
            String elevatorId, String workTypeCode, LocalDateTime checkoutTime) throws Exception {
        DispatchService service = newScheduledService();
        holdScheduledLock = true;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            lockElevator(blocker, elevatorId);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<List<WorkOrder>> scheduled = pool.submit(() -> {
                    path.set("scheduled");
                    return transactions.execute(status -> service.ensureDueOrders());
                });
                assertTrue(preLockMaintenanceChecked.await(10, TimeUnit.SECONDS),
                        "定时派单未完成锁前周期快速判断");
                assertTrue(scheduledLockAttempted.await(10, TimeUnit.SECONDS),
                        "定时派单未尝试获取电梯行锁");
                insertDoneMaintenance(blocker, elevatorId, workTypeCode, checkoutTime);
                blocker.commit();
                allowScheduledLock.countDown();
                return scheduled.get(30, TimeUnit.SECONDS);
            } finally {
                allowScheduledLock.countDown();
                pool.shutdownNow();
                blocker.rollback();
            }
        }
    }

    private DispatchService newScheduledService() throws Exception {
        WorkOrderMapper orders = synchronizedOrderMapper();
        ChecklistService checklists = new ChecklistService();
        checklists.load();
        TenantSnapshotService snapshots = new TenantSnapshotService(mock(UseUnitMapper.class),
                mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        return new DispatchService(synchronizedElevatorMapper(), orders,
                sqlSession.getMapper(MessageMapper.class), mock(EmployeeMapper.class),
                checklists, new OrderNoIssuer(orders), snapshots);
    }

    private final ThreadLocal<String> path = new ThreadLocal<>();
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean manualLocked = new AtomicBoolean();
    private final AtomicInteger scheduledActiveChecks = new AtomicInteger();
    private final CountDownLatch manualActiveChecked = new CountDownLatch(1);
    private final CountDownLatch scheduledLockedActiveCheck = new CountDownLatch(1);
    private final CountDownLatch preLockMaintenanceChecked = new CountDownLatch(1);
    private final CountDownLatch scheduledLockAttempted = new CountDownLatch(1);
    private final CountDownLatch allowScheduledLock = new CountDownLatch(1);
    private boolean holdScheduledLock;
    private final CountDownLatch deleteInflightChecked = new CountDownLatch(1);
    private final CountDownLatch allowDelete = new CountDownLatch(1);
    private final CountDownLatch dispatchRead = new CountDownLatch(1);
    private final CountDownLatch deleteRead = new CountDownLatch(1);
    private final CountDownLatch dispatchLocked = new CountDownLatch(1);
    private final CountDownLatch allowDispatch = new CountDownLatch(1);
    private volatile boolean pauseDeleteAfterInflight;
    private volatile boolean pauseDispatchAfterLock;

    private ElevatorMapper synchronizedElevatorMapper() {
        ElevatorMapper delegate = sqlSession.getMapper(ElevatorMapper.class);
        return (ElevatorMapper) Proxy.newProxyInstance(ElevatorMapper.class.getClassLoader(),
                new Class<?>[]{ElevatorMapper.class}, (proxy, method, args) -> {
                    if (holdScheduledLock && "scheduled".equals(path.get()) && "selectOne".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof LambdaQueryWrapper<?> query
                            && query.getSqlSegment().toUpperCase().contains("FOR UPDATE")) {
                        scheduledLockAttempted.countDown();
                        if (!allowScheduledLock.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("定时派单未获准继续获取电梯锁");
                        }
                    }
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
                    if ("scheduled".equals(path.get()) && "selectList".equals(method.getName())
                            && isGeneralMaintenanceRead(args)) {
                        preLockMaintenanceChecked.countDown();
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

    private static boolean isGeneralMaintenanceRead(Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof LambdaQueryWrapper<?> query)) {
            return false;
        }
        String sql = query.getSqlSegment();
        return sql.contains("elevator_id") && sql.contains("status") && sql.contains("checkout_time")
                && !sql.contains("work_type_code") && !sql.toUpperCase().contains("FOR UPDATE");
    }

    private static void lockElevator(Connection connection, String elevatorId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM elevator WHERE id = ? FOR UPDATE")) {
            statement.setString(1, elevatorId);
            statement.executeQuery();
        }
    }

    private static void insertDoneMaintenance(Connection connection, String elevatorId,
                                              String workTypeCode, LocalDateTime checkoutTime) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO work_order (id, order_no, elevator_id, work_type, work_type_code, "
                        + "plan_time, status, checkout_time) VALUES (?, ?, ?, ?, ?, ?, 'DONE', ?)")) {
            statement.setString(1, "done_" + elevatorId);
            statement.setString(2, "DONE-" + elevatorId);
            statement.setString(3, elevatorId);
            statement.setString(4, workTypeCode);
            statement.setString(5, workTypeCode);
            statement.setTimestamp(6, Timestamp.valueOf(checkoutTime));
            statement.setTimestamp(7, Timestamp.valueOf(checkoutTime));
            statement.executeUpdate();
        }
    }

    private static void insertElevator(String elevatorId, LocalDateTime lastMaintenanceAt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO elevator (id, elevator_code, elevator_name, category, region_id, "
                             + "work_type_code, interval_days, status, last_maintenance_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, elevatorId);
            statement.setString(2, "EM-" + elevatorId);
            statement.setString(3, "并发测试电梯");
            statement.setString(4, "曳引与强制驱动电梯");
            statement.setString(5, "rg_race");
            statement.setString(6, "HM");
            statement.setInt(7, 15);
            statement.setString(8, "ACTIVE");
            statement.setTimestamp(9, Timestamp.valueOf(lastMaintenanceAt));
            statement.executeUpdate();
        }
    }

    private com.cqwlw.maintenance.service.AdminArchiveService newDeleteService(
            ElevatorMapper elevators, WorkOrderMapper orders) {
        com.cqwlw.maintenance.service.AdminArchiveService target =
                new com.cqwlw.maintenance.service.AdminArchiveService(
                        mock(com.cqwlw.maintenance.mapper.CompanyMapper.class), mock(UseUnitMapper.class),
                        mock(EmployeeMapper.class), mock(com.cqwlw.maintenance.mapper.SysUserRoleMapper.class),
                        elevators, mock(com.cqwlw.maintenance.service.PhoneMutexService.class), orders,
                        mock(com.cqwlw.maintenance.service.PlatformClient.class),
                        mock(com.cqwlw.maintenance.mapper.SysOrgMapper.class),
                        mock(com.cqwlw.maintenance.service.AuditLogService.class));
        org.springframework.aop.framework.ProxyFactory factory =
                new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                transactionManager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.cqwlw.maintenance.service.AdminArchiveService) factory.getProxy();
    }

    private DispatchService newDispatchService(ElevatorMapper elevators, WorkOrderMapper orders) throws Exception {
        ChecklistService checklists = new ChecklistService();
        checklists.load();
        TenantSnapshotService snapshots = new TenantSnapshotService(mock(UseUnitMapper.class),
                mock(BizElevatorChangeMapper.class), mock(MigrationIssueService.class));
        return new DispatchService(elevators, orders, sqlSession.getMapper(MessageMapper.class),
                mock(EmployeeMapper.class), checklists, new OrderNoIssuer(orders), snapshots);
    }

    private ElevatorMapper elevatorForDelete() {
        ElevatorMapper delegate = sqlSession.getMapper(ElevatorMapper.class);
        return (ElevatorMapper) Proxy.newProxyInstance(ElevatorMapper.class.getClassLoader(),
                new Class<?>[]{ElevatorMapper.class}, (proxy, method, args) -> {
                    if ("selectById".equals(method.getName()) || "selectByIdForUpdate".equals(method.getName())) {
                        deleteRead.countDown();
                    }
                    return invoke(delegate, method, args);
                });
    }

    private ElevatorMapper dispatchElevatorMapper() {
        ElevatorMapper delegate = sqlSession.getMapper(ElevatorMapper.class);
        return (ElevatorMapper) Proxy.newProxyInstance(ElevatorMapper.class.getClassLoader(),
                new Class<?>[]{ElevatorMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("selectById".equals(method.getName())) {
                        dispatchRead.countDown();
                    }
                    if (pauseDispatchAfterLock && "selectOne".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof LambdaQueryWrapper<?> query
                            && query.getSqlSegment().toUpperCase().contains("FOR UPDATE")) {
                        dispatchLocked.countDown();
                        awaitLatch(allowDispatch);
                    }
                    return result;
                });
    }

    private WorkOrderMapper pausingOrderMapper() {
        WorkOrderMapper delegate = sqlSession.getMapper(WorkOrderMapper.class);
        return (WorkOrderMapper) Proxy.newProxyInstance(WorkOrderMapper.class.getClassLoader(),
                new Class<?>[]{WorkOrderMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (pauseDeleteAfterInflight && "selectCount".equals(method.getName())) {
                        deleteInflightChecked.countDown();
                        awaitLatch(allowDelete);
                    }
                    return result;
                });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试释放超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static long countActiveOrders() throws Exception {
        return countActiveOrders("el_race");
    }

    private static long countActiveOrders(String elevatorId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM work_order WHERE elevator_id = ? AND status <> 'DONE'")) {
            statement.setString(1, elevatorId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long countElevator(String elevatorId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM elevator WHERE id = ?")) {
            statement.setString(1, elevatorId);
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
