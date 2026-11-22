package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {

    /** 必须显式 SET openid=NULL；updateById 会跳过 null。 */
    @Update("UPDATE sys_employee SET openid = NULL WHERE openid = #{openid} AND id <> #{employeeId}")
    int unbindOpenidExcept(@Param("openid") String openid, @Param("employeeId") String employeeId);

    /** 角色/范围变更或限时授权回收时递增 scope_version，使旧 token 立即失效（spec §4.2/§5.1）。 */
    @Update("UPDATE sys_employee SET scope_version = scope_version + 1 WHERE id = #{id}")
    int bumpScopeVersion(@Param("id") String id);

    /** 忘记密码重置后清两端会话：显式 SQL 写 NULL（updateById 跳过 null 字段），旧 token 立即 401。 */
    @Update("UPDATE sys_employee SET session_id = NULL, session_admin = NULL WHERE id = #{id}")
    int clearSessions(@Param("id") String id);
}
