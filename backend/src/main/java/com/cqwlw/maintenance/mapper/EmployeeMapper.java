package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {

    /**
     * 解绑同一 openid 上的其他账号（防串号）。
     *
     * <p>必须是显式 SQL 的 {@code SET openid = NULL}：MyBatis-Plus {@code updateById} 默认
     * 跳过 null 字段（FieldStrategy.NOT_NULL），用实体写 null 是空操作，会导致
     * ①有唯一约束时后续写入撞 {@code uk_openid} 报 500；②无唯一约束时两账号共享同一 openid，
     * 串号缺陷复现。这里同时清掉所有其他持有者（含历史重复数据），而不只是 LIMIT 1。
     *
     * @return 被解绑的行数（0 = 无冲突）
     */
    @Update("UPDATE sys_employee SET openid = NULL WHERE openid = #{openid} AND id <> #{employeeId}")
    int unbindOpenidExcept(@Param("openid") String openid, @Param("employeeId") String employeeId);
}
