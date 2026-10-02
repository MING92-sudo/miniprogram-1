package com.cqwlw.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.controller.AdminAuditController;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.OpLog;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.OpLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志分页：op_log 须留痕三年，全量加载会随时间线性劣化，
 * 故分页必须下推到 SQL，且只反查当页出现过的操作人——不是整张员工表。
 */
class AdminAuditControllerTest {

    private OpLogMapper opLogMapper;
    private EmployeeMapper employeeMapper;
    private AdminAuditController controller;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯单测没有 Spring，MyBatis-Plus 实体缓存未初始化，渲染 lambda 条件会抛
        // "can not find lambda cache"。这里手工建一次，才能断言真实列名。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Employee.class);
    }

    @BeforeEach
    void setUp() {
        opLogMapper = mock(OpLogMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        controller = new AdminAuditController(opLogMapper, employeeMapper);
    }

    private OpLog log(String id, String operatorId) {
        OpLog l = new OpLog();
        l.id = id;
        l.operatorId = operatorId;
        l.method = "POST";
        l.path = "/admin/plans";
        l.action = "assign";
        l.result = "SUCCESS";
        l.ip = "10.0.0.1";
        l.createdAt = LocalDateTime.of(2026, 10, 2, 9, 0);
        return l;
    }

    @SuppressWarnings("unchecked")
    private void givenPage(List<OpLog> rows, long total) {
        Page<OpLog> page = new Page<>(2, 20);
        page.setRecords(rows);
        page.setTotal(total);
        when(opLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    }

    @Test
    void pushesPaginationDownToSqlAndUsesItsTotal() {
        // 当页 1 条，但库里共 356 条：total 必须来自 SQL 而非当页长度
        givenPage(List.of(log("ol_1", "e_1")), 356L);
        when(employeeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(employee("e_1", "张伟")));

        Map<String, Object> out = controller.logs(null, null, "2", "20").getData();

        assertEquals(356L, out.get("total"));
        assertEquals(2, out.get("page"));
        assertEquals(20, out.get("size"));
        assertEquals(1, ((List<?>) out.get("list")).size());

        ArgumentCaptor<Page<OpLog>> cap = ArgumentCaptor.forClass(Page.class);
        verify(opLogMapper).selectPage(cap.capture(), any(Wrapper.class));
        assertEquals(2L, cap.getValue().getCurrent());
        assertEquals(20L, cap.getValue().getSize());
    }

    @Test
    void resolvesOnlyTheOperatorsPresentOnTheCurrentPage() {
        givenPage(List.of(log("ol_1", "e_1"), log("ol_2", "e_2")), 356L);
        when(employeeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                employee("e_1", "张伟"), employee("e_2", "李强")));

        Map<String, Object> out = controller.logs(null, null, "1", "20").getData();
        List<?> rows = (List<?>) out.get("list");

        assertEquals("张伟", ((Map<?, ?>) rows.get(0)).get("operatorName"));
        assertEquals("李强", ((Map<?, ?>) rows.get(1)).get("operatorName"));
        // 只查这两人，而不是整张员工表
        ArgumentCaptor<LambdaQueryWrapper<Employee>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(employeeMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("id IN") || sql.contains("IN ("), sql);
        assertEquals(2, cap.getValue().getParamNameValuePairs().size(),
                "只应反查当页出现的操作人，而非整张员工表");
    }

    @Test
    void doesNotQueryEmployeesWhenPageHasNoOperator() {
        givenPage(List.of(log("ol_1", null)), 1L);

        Map<String, Object> out = controller.logs(null, null, "1", "20").getData();

        assertEquals("", ((Map<?, ?>) ((List<?>) out.get("list")).get(0)).get("operatorName"));
        verify(employeeMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void fallsBackToOperatorIdWhenEmployeeRowMissing() {
        // 员工被删除时留痕仍要可读，回落到 operatorId 而不是空串
        givenPage(List.of(log("ol_1", "e_gone")), 1L);
        when(employeeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Map<String, Object> out = controller.logs(null, null, "1", "20").getData();

        assertEquals("e_gone", ((Map<?, ?>) ((List<?>) out.get("list")).get(0)).get("operatorName"));
    }

    @Test
    void clampsNonPositivePageAndSize() {
        givenPage(List.of(), 0L);

        Map<String, Object> out = controller.logs(null, null, "0", "99999").getData();

        assertEquals(1, out.get("page"));
        assertEquals(200, out.get("size"), "size 必须有上界，否则可被一次性拉走全表");
    }

    private Employee employee(String id, String name) {
        Employee e = new Employee();
        e.id = id;
        e.name = name;
        return e;
    }
}