package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cqwlw.maintenance.auth.AuthController;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.WxAuthService;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 真实 MySQL 集成测试（未提供连接信息时自动跳过）：验证 MyBatis-Plus 生成的真实 SQL。
 *
 * <p>存在的理由：{@code updateById} 默认跳过 null 字段（FieldStrategy.NOT_NULL），
 * 用实体写 {@code openid = null} 是空操作——假 Mapper 单测抓不到这类 SQL 生成缺陷。
 * 本测试跑真实 "解绑 → 绑定" 全流程，断言旧持有者真的被置空、唯一键不再冲突。
 *
 * <p>运行方式（CI 无库时自动 skip）：
 * <pre>
 * P0_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/p0_it?createDatabaseIfNotExist=true&amp;serverTimezone=Asia/Shanghai&amp;useSSL=false
 * P0_TEST_MYSQL_USER=root
 * P0_TEST_MYSQL_PASSWORD=***
 * mvn test
 * </pre>
 */
class EmployeeMapperDbTest {

    @Test
    void bindReleasesPreviousHolderWithRealSql() throws Exception {
        String url = System.getenv("P0_TEST_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未设置 P0_TEST_MYSQL_URL，跳过真实库集成测试");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, System.getenv("P0_TEST_MYSQL_USER"), System.getenv("P0_TEST_MYSQL_PASSWORD"));
        createSchema(dataSource);
        try {
            SqlSessionFactory factory = sqlSessionFactory(dataSource);
            AppProperties props = new AppProperties();
            props.setJwtSecret("unit-test-secret-key-please-change-0123456789");
            props.setWxAppid("wxappid123");
            props.setWxAppsecret("wxsecret456");
            JwtService jwtService = new JwtService(props);

            try (SqlSession session = factory.openSession(true)) {
                EmployeeMapper mapper = session.getMapper(EmployeeMapper.class);
                mapper.insert(employee("emp_a", "13800000001", null));
                mapper.insert(employee("emp_b", "13800000002", "openid_A"));
                mapper.insert(employee("emp_c", "13800000003", "openid_A")); // 历史重复行

                RestTemplate restTemplate = new RestTemplate();
                MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
                server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                                "https://api.weixin.qq.com/sns/jscode2session")))
                        .andExpect(queryParam("js_code", "code_A"))
                        .andRespond(withSuccess("{\"openid\":\"openid_A\"}", MediaType.APPLICATION_JSON));
                AuthController controller = new AuthController(mapper, jwtService,
                        new WxAuthService(props, restTemplate));

                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader("Authorization", "Bearer " + jwtService.issue("emp_a", "WORKER", null));
                controller.bindWeChat(Map.of("code", "code_A"), request);
                server.verify();
            }

            try (SqlSession verify = factory.openSession(true)) {
                EmployeeMapper mapper = verify.getMapper(EmployeeMapper.class);
                assertEquals("openid_A", mapper.selectById("emp_a").openid);
                assertNull(mapper.selectById("emp_b").openid, "updateById 不会写 null；必须由显式 SQL 解绑");
                assertNull(mapper.selectById("emp_c").openid, "历史重复行也必须被解绑");
            }

            // V6 的第二步：数据干净后再加唯一键，此后同一 openid 再插一行必须被拒
            try (SqlSession session = factory.openSession(true)) {
                EmployeeMapper mapper = session.getMapper(EmployeeMapper.class);
                try (Connection connection = dataSource.getConnection();
                     Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE sys_employee ADD UNIQUE KEY uk_openid (openid)");
                }
                assertThrows(RuntimeException.class,
                        () -> mapper.insert(employee("emp_d", "13800000004", "openid_A")));
            }
        } finally {
            dropSchema(dataSource);
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(EmployeeMapper.class);
        return factory;
    }

    private static void createSchema(DriverManagerDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sys_employee");
            // 先建修复前的状态（普通索引、允许重复 openid），以便造出"串号"存量数据；
            // 唯一键在绑定流程验证后再加（等价 V6：先清理、再加约束）
            st.execute("CREATE TABLE sys_employee ("
                    + "id VARCHAR(32) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(64), phone VARCHAR(32), account VARCHAR(32),"
                    + "password_hash VARCHAR(100), role VARCHAR(32), role_text VARCHAR(64),"
                    + "openid VARCHAR(128) NULL, platform_id VARCHAR(64), certificate VARCHAR(64),"
                    + "work_start_date VARCHAR(10), work_end_date VARCHAR(10),"
                    + "work_stat VARCHAR(16), sync_status VARCHAR(16), enabled TINYINT(1),"
                    + "KEY idx_openid (openid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private static void dropSchema(DriverManagerDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sys_employee");
        }
    }

    private static Employee employee(String id, String phone, String openid) {
        Employee e = new Employee();
        e.id = id;
        e.name = id;
        e.phone = phone;
        e.account = phone;
        e.passwordHash = "x";
        e.role = "WORKER";
        e.roleText = "维保人员";
        e.openid = openid;
        e.enabled = true;
        return e;
    }
}
