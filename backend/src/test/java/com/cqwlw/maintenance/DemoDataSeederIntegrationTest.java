package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.DrillMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.KnowledgeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.mapper.RescueMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.service.ChecklistService;
import com.cqwlw.maintenance.service.DemoDataSeeder;
import com.cqwlw.maintenance.service.WorkOrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_IT_MYSQL_SERVER_URL", matches = "^jdbc:mysql://.*")
class DemoDataSeederIntegrationTest {

    private static String serverUrl;
    private static String username;
    private static String password;
    private static String databaseName;
    private static DataSource dataSource;
    private static SqlSessionTemplate sqlSession;

    @BeforeAll
    static void createDisposableDatabase() throws Exception {
        serverUrl = System.getenv("ARCHIVE_IT_MYSQL_SERVER_URL");
        username = System.getenv().getOrDefault("ARCHIVE_IT_MYSQL_USERNAME", "root");
        password = System.getenv().getOrDefault("ARCHIVE_IT_MYSQL_PASSWORD", "");
        String serverPath = serverUrl.split("\\?", 2)[0];
        if (!serverPath.endsWith("/")) {
            throw new IllegalArgumentException("ARCHIVE_IT_MYSQL_SERVER_URL 只能指向服务器，不得包含既有数据库名");
        }
        databaseName = "seed_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
            configuration.addMapper(EmployeeMapper.class);
            configuration.addMapper(UseUnitMapper.class);
            configuration.addMapper(ElevatorMapper.class);
            configuration.addMapper(WorkOrderMapper.class);
            configuration.addMapper(MaintainRecordMapper.class);
            configuration.addMapper(MessageMapper.class);
            configuration.addMapper(RescueMapper.class);
            configuration.addMapper(FaultMapper.class);
            configuration.addMapper(DrillMapper.class);
            configuration.addMapper(InspectRecordMapper.class);
            configuration.addMapper(KnowledgeMapper.class);
            configuration.addMapper(SysOrgMapper.class);
            configuration.addMapper(SysUserRoleMapper.class);
            configuration.addMapper(ClientAccountMapper.class);
            factory.setConfiguration(configuration);
            sqlSession = new SqlSessionTemplate(factory.getObject());
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
            throw new IllegalStateException("临时种子测试库清理失败: " + databaseName, e);
        }
    }

    @Test
    void seedsEmptyMigratedDatabaseWithoutConstraintFailures() throws Exception {
        AppProperties props = new AppProperties();
        props.setSeedDemoData(true);
        ChecklistService checklists = new ChecklistService();
        checklists.load();
        DemoDataSeeder seeder = new DemoDataSeeder(props,
                sqlSession.getMapper(CompanyMapper.class), sqlSession.getMapper(EmployeeMapper.class),
                sqlSession.getMapper(UseUnitMapper.class), sqlSession.getMapper(ElevatorMapper.class),
                sqlSession.getMapper(WorkOrderMapper.class), sqlSession.getMapper(MaintainRecordMapper.class),
                sqlSession.getMapper(MessageMapper.class), sqlSession.getMapper(RescueMapper.class),
                sqlSession.getMapper(FaultMapper.class), sqlSession.getMapper(DrillMapper.class),
                sqlSession.getMapper(InspectRecordMapper.class), sqlSession.getMapper(KnowledgeMapper.class),
                checklists, mock(WorkOrderService.class), sqlSession.getMapper(SysOrgMapper.class),
                sqlSession.getMapper(SysUserRoleMapper.class), sqlSession.getMapper(ClientAccountMapper.class));

        seeder.run(null);

        long employees = scalar("SELECT COUNT(*) FROM sys_employee");
        long platformIds = scalar("SELECT COUNT(platform_id) FROM sys_employee");
        long distinctPlatformIds = scalar("SELECT COUNT(DISTINCT platform_id) FROM sys_employee");
        assertTrue(employees > 0);
        assertEquals(platformIds, distinctPlatformIds);
        assertEquals(1L, scalar("SELECT COUNT(*) FROM sys_employee WHERE id = 'emp_lzg'"));
    }

    private static long scalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
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
