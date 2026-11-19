package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.ClientAccount;
import org.apache.ibatis.annotations.Mapper;

/** P3 客户账号数据访问（独立于 EmployeeMapper，客户不进员工表）。 */
@Mapper
public interface ClientAccountMapper extends BaseMapper<ClientAccount> {
}
