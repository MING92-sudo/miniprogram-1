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

/**
 * 使用单位门户账号迁移（V34 遗留 use_unit.account → P3 客户账号表）。
 *
 * <p>口径修正（2026-11-19）：V41 曾把门户账号并进 sys_employee（角色 CLIENT_ADMIN），
 * 与 P3「客户账号独立于员工体系」（docs/14 §6，R7）冲突——同一个人出现两套登录身份，
 * 停用一处另一处仍可登录。现统一迁移目标为 <b>client_account</b>，员工表不再承载客户主体。
 *
 * <p>规则：
 * <ul>
 *   <li>手机号已被内部员工占用：不开通（跨主体互斥，R2/R7），保持 account_migrated=0 待人工处理；</li>
 *   <li>同单位同手机号已有客户账号：幂等跳过，只补标记；</li>
 *   <li>口令沿用门户口令哈希；缺失时用手机号后 6 位（与 ClientAccountService 初始口令同口径）；</li>
 *   <li>同一单位内 openid 已被其他客户账号占用则不复制（不抢夺微信绑定）；</li>
 *   <li>只 INSERT/UPDATE，不 DELETE（审计留存）。</li>
 * </ul>
 */
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
