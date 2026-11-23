package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.AuditLogService;
import com.cqwlw.maintenance.service.PhoneMutexService;
import com.cqwlw.maintenance.service.PlatformClient;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class AdminArchiveTransactionIntegrationTest {

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
        databaseName = "archive_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            configuration.addMapper(CompanyMapper.class);
            configuration.addMapper(UseUnitMapper.class);
            configuration.addMapper(EmployeeMapper.class);
            configuration.addMapper(SysUserRoleMapper.class);
            configuration.addMapper(ElevatorMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
            configuration.addMapper(SysOrgMapper.class);
            configuration.addMapper(ClientAccountMapper.class);
            configuration.addMapper(MaintainRecordMapper.class);
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
            throw new IllegalStateException("临时档案事务测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void createEmployeeRoleFailureLeavesNoEmployeeRow() throws Exception {
        AdminArchiveService service = transactionalService(service(
                failingMapper(SysUserRoleMapper.class, "insert", 1),
                sqlSession.getMapper(ClientAccountMapper.class),
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(ElevatorMapper.class)));

        assertThrows(IllegalStateException.class, () -> service.createEmployee(Map.of(
                "name", "事务集成", "phone", "13900009101", "role", "WORKER")));

        assertEquals(0L, count("SELECT COUNT(*) FROM sys_employee WHERE phone = ?", "13900009101"));
    }

    @Test
    void updateEmployeeRoleFailureRestoresEmployeeAndRoleRows() throws Exception {
        execute("INSERT INTO sys_employee (id, name, phone, account, password_hash, role, role_text) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "emp_it_update", "原员工", "13900009102", "emp_it_update", "not-used", "WORKER", "维保员");
        execute("INSERT INTO sys_user_role (id, user_id, user_type, role_code, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "ur_it_update", "emp_it_update", "EMPLOYEE", "WORKER", testTime());
        AdminArchiveService service = transactionalService(service(
                failingMapper(SysUserRoleMapper.class, "insert", 1),
                sqlSession.getMapper(ClientAccountMapper.class),
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(ElevatorMapper.class)));

        assertThrows(IllegalStateException.class, () -> service.updateEmployee(
                "emp_it_update", Map.of("role", "MAINT_SUP"), "emp_admin"));

        assertEquals("WORKER", scalar("SELECT role FROM sys_employee WHERE id = ?", "emp_it_update"));
        assertEquals("WORKER", scalar("SELECT role_code FROM sys_user_role WHERE user_id = ?",
                "emp_it_update"));
    }

    @Test
    void deleteUseUnitFailureRestoresUnitAccountsAndRecordLinks() throws Exception {
        execute("INSERT INTO use_unit (id, unit_name) VALUES (?, ?)", "uu_it_delete", "事务单位");
        execute("INSERT INTO client_account (id, use_unit_id, name, phone, password_hash, role_code, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ca_it_delete", "uu_it_delete", "客户", "13900009103", "not-used", "CLIENT_ADMIN", testTime());
        execute("INSERT INTO maintain_record (id, use_unit_id, client_account_id, "
                        + "refused_by_client_account_id, created_at) VALUES (?, ?, ?, ?, ?)",
                "mr_it_delete", "uu_it_delete", "ca_it_delete", "ca_it_delete", testTime());
        AdminArchiveService service = transactionalService(service(
                sqlSession.getMapper(SysUserRoleMapper.class),
                failingMapper(ClientAccountMapper.class, "delete", 1),
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(ElevatorMapper.class)));

        assertThrows(IllegalStateException.class, () -> service.deleteUseUnit("uu_it_delete"));

        assertEquals(1L, count("SELECT COUNT(*) FROM use_unit WHERE id = ?", "uu_it_delete"));
        assertEquals(1L, count("SELECT COUNT(*) FROM client_account WHERE id = ?", "ca_it_delete"));
        assertEquals("ca_it_delete", scalar(
                "SELECT client_account_id FROM maintain_record WHERE id = ?", "mr_it_delete"));
        assertEquals("ca_it_delete", scalar(
                "SELECT refused_by_client_account_id FROM maintain_record WHERE id = ?", "mr_it_delete"));
    }

    @Test
    void batchGeoFailureRollsBackEarlierCoordinateUpdates() throws Exception {
        insertElevator("el_it_geo_1", "EM-IT-GEO-1");
        insertElevator("el_it_geo_2", "EM-IT-GEO-2");
        AdminArchiveService service = transactionalService(service(
                sqlSession.getMapper(SysUserRoleMapper.class),
                sqlSession.getMapper(ClientAccountMapper.class),
                sqlSession.getMapper(MaintainRecordMapper.class),
                failingMapper(ElevatorMapper.class, "updateById", 2)));

        assertThrows(IllegalStateException.class, () -> service.batchGeo(Map.of("items", List.of(
                Map.of("code", "EM-IT-GEO-1", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "EM-IT-GEO-2", "lng", "106.56", "lat", "29.57")))));

        assertEquals("100.000001", scalar("SELECT lng FROM elevator WHERE id = ?", "el_it_geo_1").toString());
        assertEquals("20.000001", scalar("SELECT lat FROM elevator WHERE id = ?", "el_it_geo_1").toString());
    }

    @Test
    void batchGeoBusinessFailuresKeepValidRows() throws Exception {
        insertElevator("el_it_partial", "EM-IT-PARTIAL");
        AdminArchiveService service = transactionalService(service(
                sqlSession.getMapper(SysUserRoleMapper.class),
                sqlSession.getMapper(ClientAccountMapper.class),
                sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(ElevatorMapper.class)));

        Map<String, Object> result = service.batchGeo(Map.of("items", List.of(
                Map.of("code", "EM-IT-PARTIAL", "lng", "106.55", "lat", "29.56"),
                Map.of("code", "EM-IT-PARTIAL", "lng", "181", "lat", "29.56"))));

        assertEquals(1, ((List<?>) result.get("success")).size());
        assertEquals(1, ((List<?>) result.get("failed")).size());
        assertEquals("106.550000", scalar("SELECT lng FROM elevator WHERE id = ?", "el_it_partial").toString());
        assertEquals("29.560000", scalar("SELECT lat FROM elevator WHERE id = ?", "el_it_partial").toString());
    }

    private static AdminArchiveService service(SysUserRoleMapper roles, ClientAccountMapper accounts,
                                               MaintainRecordMapper records, ElevatorMapper elevators) {
        CompanyMapper companies = sqlSession.getMapper(CompanyMapper.class);
        UseUnitMapper units = sqlSession.getMapper(UseUnitMapper.class);
        EmployeeMapper employees = sqlSession.getMapper(EmployeeMapper.class);
        AdminArchiveService service = new AdminArchiveService(companies, units, employees, roles, elevators,
                new PhoneMutexService(companies, units, employees), sqlSession.getMapper(WorkOrderMapper.class),
                mock(PlatformClient.class), sqlSession.getMapper(SysOrgMapper.class), mock(AuditLogService.class));
        service.setClientAccountMapper(accounts);
        service.setMaintainRecordMapper(records);
        return service;
    }

    private static AdminArchiveService transactionalService(AdminArchiveService service) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (AdminArchiveService) factory.getProxy();
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

    private static void insertElevator(String id, String code) throws Exception {
        execute("INSERT INTO elevator (id, elevator_code, elevator_name, lng, lat, geo_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, code, "事务电梯", "100.000001", "20.000001", "SELF_COLLECTED");
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

    private static long count(String sql, Object... params) throws Exception {
        return ((Number) scalar(sql, params)).longValue();
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
