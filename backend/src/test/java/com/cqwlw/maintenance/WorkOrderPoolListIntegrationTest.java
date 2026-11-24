package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.HandoverOrderMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.SysParamMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.CheckinThresholdService;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DISPATCH_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class WorkOrderPoolListIntegrationTest {

    private static final String EMP_WORKER = "emp_pool_worker";
    private static final String PID_WORKER = "pid_pool_worker";

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("DISPATCH_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("DISPATCH_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("DISPATCH_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        if (!serverPath.endsWith("/")) {
            throw new IllegalArgumentException("DISPATCH_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "work_order_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            configuration.addInterceptor(pagination);
            configuration.addMapper(EmployeeMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
            factory.setConfiguration(configuration);
            sqlSession = new SqlSessionTemplate(factory.getObject());
        } catch (Exception e) {
            dropDisposableDatabase();
            throw e;
        }
    }

    @BeforeEach
    void cleanDomainRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM work_order");
            statement.executeUpdate("DELETE FROM sys_employee");
        }
        execute("INSERT INTO sys_employee (id, name, phone, account, password_hash, role, role_text, platform_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                EMP_WORKER, "池化工人", "13900009201", EMP_WORKER, "not-used", "WORKER", "维保员", PID_WORKER);
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
            throw new IllegalStateException("临时工单列表测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void nullWorkerAndRegionPoolOrderMatchesSqlListAndMemoryScope() {
        insertPoolOrder("wo_pool_null", null);

        Map<String, Object> result = service().listOrders(emptyQuery(), EMP_WORKER);

        assertEquals(1, result.get("total"));
        assertEquals("wo_pool_null", ((List<Map<String, Object>>) result.get("list")).get(0).get("id"));
        assertEquals(result.get("total"), service().scopeOrders(
                sqlSession.getMapper(WorkOrderMapper.class).selectList(null), EMP_WORKER).size());
    }

    @Test
    void sqlPoolScopeKeepsRegionBoundary() {
        execute("UPDATE sys_employee SET region_id = 'rg_1' WHERE id = ?", EMP_WORKER);
        insertPoolOrder("wo_pool_same_region", "rg_1");
        insertPoolOrder("wo_pool_other_region", "rg_2");

        Map<String, Object> result = service().listOrders(emptyQuery(), EMP_WORKER);

        assertEquals(1, result.get("total"));
        assertEquals("wo_pool_same_region",
                ((List<Map<String, Object>>) result.get("list")).get(0).get("id"));
    }

    @Test
    void claimedPoolOrderRemainsVisibleToClaimantAndCannotBeClaimedAgain() {
        insertPoolOrder("wo_pool_claim", null);
        WorkOrderMapper orders = sqlSession.getMapper(WorkOrderMapper.class);

        assertEquals(1, orders.claimOrder("wo_pool_claim", "池化工人", PID_WORKER));
        assertEquals(0, orders.claimOrder("wo_pool_claim", "其他工人", "pid_other"));
        Map<String, Object> result = service().listOrders(emptyQuery(), EMP_WORKER);

        assertEquals(1, result.get("total"));
        assertEquals("wo_pool_claim", ((List<Map<String, Object>>) result.get("list")).get(0).get("id"));
    }

    private static WorkOrderService service() {
        EmployeeMapper employees = sqlSession.getMapper(EmployeeMapper.class);
        return new WorkOrderService(sqlSession.getMapper(WorkOrderMapper.class), mock(ElevatorMapper.class),
                mock(UseUnitMapper.class), employees, mock(CompanyMapper.class), mock(MaintainRecordMapper.class),
                mock(FaultMapper.class), mock(InspectRecordMapper.class), mock(HandoverOrderMapper.class),
                mock(MessageMapper.class), mock(ChecklistService.class), mock(PlatformReportService.class),
                new EmployeeScopeService(employees),
                new CheckinThresholdService(mock(SysParamMapper.class), new AppProperties()),
                mock(FileStorageService.class), TxTestSupport.noopTx(), new AppProperties());
    }

    private static Map<String, String> emptyQuery() {
        return Map.of("page", "1", "size", "20", "due", "", "status", "", "keyword", "");
    }

    private static void insertPoolOrder(String id, String regionId) {
        execute("INSERT INTO work_order (id, order_no, elevator_id, work_type, work_type_code, plan_time, "
                        + "status, worker_platform_id, region_id) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, "
                        + "'PENDING', NULL, ?)",
                id, "WO-" + id, "el_pool", "半月维保", "HM", regionId);
    }

    private static void execute(String sql, Object... args) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        return query < 0 ? baseUrl + database
                : baseUrl.substring(0, query) + database + baseUrl.substring(query);
    }
}
