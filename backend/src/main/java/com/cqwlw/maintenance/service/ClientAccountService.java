package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 安全红线：客户账号不进 sys_employee（R7）； */
@Service
public class ClientAccountService {

    private static final Logger log = LoggerFactory.getLogger(ClientAccountService.class);

    private final ClientAccountMapper mapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeMapper employeeMapper;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ClientAccountService(ClientAccountMapper mapper, UseUnitMapper useUnitMapper,
                                EmployeeMapper employeeMapper, AuditLogService auditLogService) {
        this.mapper = mapper;
        this.useUnitMapper = useUnitMapper;
        this.employeeMapper = employeeMapper;
        this.auditLogService = auditLogService;
    }

    private static String initialPassword(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : "123456";
    }

    // ── 内部管理端点 ───────────────────────────────────────────────

    /** 某单位的客户账号列表（内部只读投影：不返回口令哈希/session/openid 全文） */
    public List<Map<String, Object>> listByUnit(String useUnitId) {
        List<ClientAccount> rows = mapper.selectList(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getUseUnitId, useUnitId)
                .orderByDesc(ClientAccount::getCreatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClientAccount c : rows) {
            out.add(row(c));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(String useUnitId, Map<String, Object> body, String operatorId) {
        UseUnit unit = useUnitMapper.selectById(useUnitId);
        if (unit == null) {
            throw new BizException(1404, "使用单位档案不存在");
        }
        String name = str(body.get("name"));
        String phone = str(body.get("phone")).replaceAll("\\s", "");
        String role = str(body.get("role"));
        if (name.isEmpty()) {
            throw new BizException(422, "客户联系人姓名必填");
        }
        if (!phone.matches("1\\d{10}")) {
            throw new BizException(422, "手机号格式不正确");
        }
        if (role.isEmpty()) {
            role = ClientAccount.ROLE_ADMIN;
        }
        if (!ClientAccount.ROLE_ADMIN.equals(role) && !ClientAccount.ROLE_SIGNER.equals(role)) {
            throw new BizException(422, "客户角色仅支持 CLIENT_ADMIN/CLIENT_SIGNER");
        }
        requirePhoneAvailable(useUnitId, phone, null);
        String rawPwd = str(body.get("password"));
        if (rawPwd.isEmpty()) {
            rawPwd = initialPassword(phone);
        }
        if (rawPwd.length() < 6) {
            throw new BizException(422, "初始口令至少 6 位");
        }

        ClientAccount c = new ClientAccount();
        c.id = Ids.next("ca");
        c.useUnitId = useUnitId;
        c.name = name;
        c.phone = phone;
        c.passwordHash = encoder.encode(rawPwd);
        c.openid = blankToNull(str(body.get("openid")));
        c.roleCode = role;
        c.status = ClientAccount.STATUS_ACTIVE;
        c.enabled = 1;
        c.scopeVersion = 1;
        c.createdBy = blankToNull(operatorId);
        c.createdAt = TimeUtil.now();
        mapper.insert(c);
        audit(operatorId, useUnitId, "CLIENT_ACCOUNT_CREATE", c.id,
                "开通客户账号 " + name + "（" + role + "）", true);
        log.info("开通客户账号: accountId={}, unitId={}, role={}", c.id, useUnitId, role);
        Map<String, Object> out = row(c);
        // 初始口令仅开通响应回显一次，便于内部转交；不落库明文、不进列表
        out.put("initialPassword", rawPwd);
        return out;
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body, String operatorId) {
        ClientAccount c = findOr404(id);
        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name.isEmpty()) {
                throw new BizException(422, "客户联系人姓名必填");
            }
            c.name = name;
        }
        if (body.containsKey("role")) {
            String role = str(body.get("role"));
            if (!ClientAccount.ROLE_ADMIN.equals(role) && !ClientAccount.ROLE_SIGNER.equals(role)) {
                throw new BizException(422, "客户角色仅支持 CLIENT_ADMIN/CLIENT_SIGNER");
            }
            if (!role.equals(c.roleCode)) {
                c.roleCode = role;
                invalidateSession(c); // 角色变更 → 旧 token 失效
            }
        }
        if (body.containsKey("phone")) {
            String phone = str(body.get("phone")).replaceAll("\\s", "");
            if (!phone.matches("1\\d{10}")) {
                throw new BizException(422, "手机号格式不正确");
            }
            if (!phone.equals(c.phone)) {
                requirePhoneAvailable(c.useUnitId, phone, c.id);
                c.phone = phone;
                invalidateSession(c);
            }
        }
        c.updatedAt = TimeUtil.now();
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_UPDATE", c.id, "修改客户账号 " + c.name, true);
        return row(c);
    }

    /** 停用（不删除）：立即清会话 + 递增 scope_version，旧 token 401；MANUAL 停用不被合同自动恢复。 */
    @Transactional
    public Map<String, Object> disable(String id, String operatorId, String reason) {
        ClientAccount c = findOr404(id);
        c.status = ClientAccount.STATUS_DISABLED;
        c.enabled = 0;
        c.disabledAt = TimeUtil.now();
        c.disabledReason = "MANUAL_" + (reason == null || reason.isBlank() ? "OPERATOR" : sanitize(reason));
        invalidateSession(c);
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_DISABLE", c.id, "停用客户账号 " + c.name, true);
        return row(c);
    }

    /** 重新启用：校验同单位活跃手机号冲突；人工停用的必须显式调用本方法恢复。 */
    @Transactional
    public Map<String, Object> enable(String id, String operatorId) {
        ClientAccount c = findOr404(id);
        requirePhoneAvailable(c.useUnitId, c.phone, c.id);
        c.status = ClientAccount.STATUS_ACTIVE;
        c.enabled = 1;
        c.disabledAt = null;
        c.disabledReason = null;
        c.graceUntil = null;
        invalidateSession(c);
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_ENABLE", c.id, "启用客户账号 " + c.name, true);
        return row(c);
    }

    /** 重置口令：新口令 ≥6 位，清会话；返回明文一次供转交，不落库明文。 */
    @Transactional
    public Map<String, Object> resetPassword(String id, Map<String, Object> body, String operatorId) {
        ClientAccount c = findOr404(id);
        String raw = str(body.get("password"));
        if (raw.isEmpty()) {
            raw = initialPassword(c.phone);
        }
        if (raw.length() < 6) {
            throw new BizException(422, "新口令至少 6 位");
        }
        c.passwordHash = encoder.encode(raw);
        c.updatedAt = TimeUtil.now();
        invalidateSession(c);
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_RESET_PWD", c.id, "重置客户账号口令 " + c.name, true);
        Map<String, Object> out = row(c);
        out.put("password", raw);
        return out;
    }

    /** 换绑微信：openid 只接受服务端 code 换取后的值（由 Controller 调 WxAuthService 后传入），清会话。 */
    @Transactional
    public Map<String, Object> bindWechat(String id, String openid, String operatorId) {
        ClientAccount c = findOr404(id);
        String oid = blankToNull(openid);
        // 同一单位内活跃 openid 唯一
        if (oid != null) {
            ClientAccount other = mapper.selectOne(new LambdaQueryWrapper<ClientAccount>()
                    .eq(ClientAccount::getUseUnitId, c.useUnitId)
                    .eq(ClientAccount::getOpenid, oid)
                    .ne(ClientAccount::getId, c.id)
                    .last("LIMIT 1"));
            if (other != null && other.isActive()) {
                throw new BizException(1002, "该微信已绑定本单位其他客户账号");
            }
        }
        c.openid = oid;
        invalidateSession(c);
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_BIND_WX", c.id, "换绑客户账号微信 " + c.name, true);
        return row(c);
    }

    @Transactional
    public Map<String, Object> restoreGrace(String id, Map<String, Object> body, String operatorId) {
        ClientAccount c = findOr404(id);
        if (!ClientAccount.STATUS_DISABLED.equals(c.status)) {
            throw new BizException(422, "仅停用账号可临时恢复宽限");
        }
        String until = str(body.get("graceUntil"));
        LocalDateTime graceUntil = TimeUtil.parse(until);
        if (graceUntil == null) {
            graceUntil = TimeUtil.now().plusDays(30);
        }
        c.status = ClientAccount.STATUS_GRACE;
        c.enabled = 1;
        c.graceUntil = graceUntil;
        c.disabledAt = null;
        c.disabledReason = null;
        invalidateSession(c);
        mapper.updateById(c);
        audit(operatorId, c.useUnitId, "CLIENT_ACCOUNT_RESTORE_GRACE", c.id,
                "临时恢复宽限至 " + TimeUtil.format(graceUntil), true);
        return row(c);
    }

    // ── 登录/鉴权侧查询（供 AuthController / AuthInterceptor 使用）─────────────

    public List<ClientAccount> findByPhone(String phone) {
        return mapper.selectList(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getPhone, phone));
    }

    /** 按 openid 查所有客户账号（微信登录候选） */
    public List<ClientAccount> findByOpenid(String openid) {
        return mapper.selectList(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getOpenid, openid));
    }

    public ClientAccount findById(String id) {
        return mapper.selectById(id);
    }

    /** 记录会话 id（单账号单会话，客户账号独立维度） */
    public void issueSession(String id, String sessionId) {
        ClientAccount c = findOr404(id);
        c.sessionId = sessionId;
        c.lastLoginAt = TimeUtil.now();
        mapper.updateById(c);
    }

    /** 微信验证自助重置（忘记密码，免旧口令）：新口令 ≥6 位，清会话（旧 token 立即 401）。 */
    @Transactional
    public void resetPasswordAfterWechatVerify(String id, String newPassword) {
        ClientAccount c = findOr404(id);
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        c.passwordHash = encoder.encode(newPassword);
        c.updatedAt = TimeUtil.now();
        invalidateSession(c);
        mapper.updateById(c);
        auditSystem(c.useUnitId, "CLIENT_ACCOUNT_RESET_PWD_WECHAT", c.id,
                "微信验证自助重置口令 " + (c.name == null ? c.phone : c.name));
    }

    /** 客户自助改密：验原口令 + 新口令 ≥6 位，清会话（旧 token 401）。由 BCrypt 校验，错误 422。 */
    public void changePassword(String id, String oldPwd, String newPwd) {
        ClientAccount c = findOr404(id);
        if (oldPwd == null || !encoder.matches(oldPwd, c.passwordHash)) {
            throw new BizException(422, "原密码不正确");
        }
        if (newPwd == null || newPwd.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        if (newPwd.equals(oldPwd)) {
            throw new BizException(422, "新密码不能与原密码相同");
        }
        c.passwordHash = encoder.encode(newPwd);
        invalidateSession(c);
        mapper.updateById(c);
    }

    // ── 内部校验 ───────────────────────────────────────────────

    private void requirePhoneAvailable(String useUnitId, String phone, String excludeId) {
        ClientAccount dup = mapper.selectOne(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getUseUnitId, useUnitId)
                .eq(ClientAccount::getPhone, phone)
                .ne(ClientAccount::getId, excludeId == null ? "" : excludeId)
                .last("LIMIT 1"));
        if (dup != null && dup.isActive()) {
            throw new BizException(1002, "该手机号在本单位已开通客户账号");
        }
        Employee emp = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPhone, phone).last("LIMIT 1"));
        if (emp != null) {
            throw new BizException(1002, "该手机号已绑定内部员工账号，不能开通客户账号");
        }
    }

    private void invalidateSession(ClientAccount c) {
        c.sessionId = null;
        c.scopeVersion = (c.scopeVersion == null ? 1 : c.scopeVersion) + 1;
    }

    /** 幂等：账号不存在直接返回，不抛错（注销请求不因账号已被删而失败）。 */
    public void revokeSession(String id) {
        ClientAccount c = id == null || id.isBlank() ? null : mapper.selectById(id);
        if (c == null) {
            return;
        }
        invalidateSession(c);
        mapper.updateById(c);
    }

    private ClientAccount findOr404(String id) {
        ClientAccount c = mapper.selectById(id);
        if (c == null) {
            throw new BizException(1404, "客户账号不存在");
        }
        return c;
    }

    private void audit(String actorId, String useUnitId, String action, String target, String reason, boolean ok) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.id = Ids.next("audit");
            a.actorId = actorId;
            a.actorRole = "EMPLOYEE";
            a.action = action;
            a.target = target;
            a.useUnitId = useUnitId;
            a.reason = reason;
            a.result = ok ? "SUCCESS" : "FAILED";
            a.createdAt = TimeUtil.now();
            auditLogService.append(a);
        } catch (Exception e) {
            // 审计落库失败不影响主流程（AuditLogService 契约：吞异常），但记日志
            log.warn("客户账号审计写入失败: action={}, target={}", action, target);
        }
    }

    /** 管理端投影：禁返 password_hash/session_id/openid 全文/shareToken 等敏感字段 */
    private Map<String, Object> row(ClientAccount c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("useUnitId", c.useUnitId);
        m.put("name", c.name);
        m.put("phone", c.phone);
        m.put("role", c.roleCode);
        m.put("roleText", ClientAccount.ROLE_ADMIN.equals(c.roleCode) ? "使用单位管理员" : "使用单位签字人");
        m.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        m.put("status", c.status);
        m.put("graceUntil", TimeUtil.format(c.graceUntil));
        m.put("enabled", c.enabled);
        m.put("wechatBound", c.openid != null && !c.openid.isBlank());
        m.put("createdBy", c.createdBy == null ? "" : c.createdBy);
        m.put("createdAt", TimeUtil.format(c.createdAt));
        m.put("disabledReason", c.disabledReason == null ? "" : c.disabledReason);
        m.put("lastLoginAt", TimeUtil.format(c.lastLoginAt));
        return m;
    }

    /** 幂等（只处理 status=GRACE 且 grace_until &lt; now）； */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 10 2 * * ?", zone = "Asia/Shanghai")
    @Transactional
    public int sweepExpiredGrace() {
        LocalDateTime now = TimeUtil.now();
        List<ClientAccount> expired = mapper.selectList(new LambdaQueryWrapper<ClientAccount>()
                .eq(ClientAccount::getStatus, ClientAccount.STATUS_GRACE)
                .isNotNull(ClientAccount::getGraceUntil)
                .lt(ClientAccount::getGraceUntil, now));
        for (ClientAccount c : expired) {
            c.status = ClientAccount.STATUS_DISABLED;
            c.enabled = 0;
            c.disabledAt = now;
            c.disabledReason = "GRACE_EXPIRED";
            invalidateSession(c);
            mapper.updateById(c);
            auditSystem(c.useUnitId, "CLIENT_ACCOUNT_GRACE_EXPIRED", c.id,
                    "宽限期届满自动停用 " + (c.name == null ? c.phone : c.name));
        }
        if (!expired.isEmpty()) {
            log.info("[grace-sweep] 自动停用 {} 个宽限期届满的客户账号", expired.size());
        }
        return expired.size();
    }

    private void auditSystem(String useUnitId, String action, String target, String reason) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.id = Ids.next("audit");
            a.actorId = "SYSTEM";
            a.actorRole = "SYSTEM";
            a.action = action;
            a.target = target;
            a.useUnitId = useUnitId;
            a.reason = reason;
            a.result = "SUCCESS";
            a.createdAt = TimeUtil.now();
            auditLogService.append(a);
        } catch (Exception e) {
            log.warn("客户账号系统审计写入失败: action={}, target={}", action, target);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\r\\n]", " ").substring(0, Math.min(80, s.length()));
    }
}
