package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** </li><li>同单位同手机号已有客户账号：幂等跳过，只补标记； */
@Service
public class UnitAccountUnifyService {

    private static final Logger log = LoggerFactory.getLogger(UnitAccountUnifyService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final ClientAccountMapper clientAccountMapper;

    public UnitAccountUnifyService(UseUnitMapper useUnitMapper, EmployeeMapper employeeMapper,
                                   ClientAccountMapper clientAccountMapper) {
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.clientAccountMapper = clientAccountMapper;
    }

    @PostConstruct
    public void onStartup() {
        int n = unifyPending();
        if (n > 0) {
            log.info("门户账号迁移：本次开通 {} 个客户账号", n);
        }
    }

    @Transactional
    public int unifyPending() {
        int n = 0;
        for (UseUnit u : useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>()
                .isNotNull(UseUnit::getAccount).eq(UseUnit::getAccountMigrated, 0))) {
            String phone = u.account == null ? "" : u.account.trim();
            if (phone.isEmpty()) {
                continue;
            }
            // 跨主体互斥：内部员工已占用该手机号，不能开通客户账号
            Employee emp = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                    .eq(Employee::getPhone, phone).last("LIMIT 1"));
            if (emp != null) {
                log.warn("门户账号 {} 已被内部员工占用，跳过客户账号开通", mask(phone));
                continue;
            }
            ClientAccount exist = clientAccountMapper.selectOne(new LambdaQueryWrapper<ClientAccount>()
                    .eq(ClientAccount::getUseUnitId, u.id)
                    .eq(ClientAccount::getPhone, phone)
                    .last("LIMIT 1"));
            if (exist == null) {
                ClientAccount c = new ClientAccount();
                c.id = Ids.next("ca");
                c.useUnitId = u.id;
                c.name = u.unitName == null || u.unitName.isBlank() ? phone : u.unitName;
                c.phone = phone;
                c.passwordHash = u.passwordHash != null && !u.passwordHash.isBlank()
                        ? u.passwordHash : encoder.encode(initialPassword(phone));
                c.openid = freeOpenid(u, phone);
                c.roleCode = ClientAccount.ROLE_ADMIN;
                c.status = ClientAccount.STATUS_ACTIVE;
                c.enabled = 1;
                c.scopeVersion = 1;
                c.createdAt = TimeUtil.now();
                clientAccountMapper.insert(c);
            }
            u.accountMigrated = 1;
            useUnitMapper.updateById(u);
            n++;
        }
        return n;
    }

    /** 同单位内 openid 未被其他客户账号占用才复制，避免抢夺微信绑定。 */
    private String freeOpenid(UseUnit u, String phone) {
        String oid = u.openid == null || u.openid.isBlank() ? null : u.openid;
        if (oid == null) {
            return null;
        }
        Long held = clientAccountMapper.selectCount(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getUseUnitId, u.id)
                .eq(ClientAccount::getOpenid, oid)
                .ne(ClientAccount::getPhone, phone));
        return held != null && held > 0 ? null : oid;
    }

    private static String initialPassword(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : "123456";
    }

    private static String mask(String phone) {
        return phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : phone;
    }
}
