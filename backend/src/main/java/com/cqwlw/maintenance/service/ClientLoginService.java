package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单账号单会话；令牌 subject 使用 client_account.id。 */
@Service
public class ClientLoginService {

    /** 客户能力集：我的电梯只读 / 记录签字 / 报告下载 / 一键报修 */
    public static final List<String> CLIENT_PERMS =
            List.of("client:elevator_view", "client:sign", "client:download", "client:report");
    /** 客户默认工作台（与前端 config/menus.HOME_BY_ROLE 同口径） */
    public static final String CLIENT_HOME = "/pages/client/home/index";

    private final ClientAccountService clientService;
    private final UseUnitMapper useUnitMapper;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ClientLoginService(ClientAccountService clientService, UseUnitMapper useUnitMapper,
                              JwtService jwtService) {
        this.clientService = clientService;
        this.useUnitMapper = useUnitMapper;
        this.jwtService = jwtService;
    }

    /** 手机号是否开通过客户账号（含已停用）：用于统一登录页区分「账号不存在」与「已停用/口令错」 */
    public boolean hasAnyAccount(String phone) {
        return !clientService.findByPhone(phone).isEmpty();
    }

    /** 该微信是否绑定过活跃客户账号（统一登录页/忘记密码分流用，不返回账号明细） */
    public boolean hasAnyAccountByOpenid(String openid) {
        return !activeAccountsByOpenid(openid).isEmpty();
    }

    /** 该微信绑定的活跃客户账号（可能跨单位多个，按单位选择） */
    public List<ClientAccount> activeAccountsByOpenid(String openid) {
        List<ClientAccount> out = new ArrayList<>();
        for (ClientAccount c : clientService.findByOpenid(openid)) {
            if (c.isActive()) {
                out.add(c);
            }
        }
        return out;
    }

    /** 忘记密码（微信验证后）：重置指定客户账号口令并清会话 */
    public void resetPasswordByWechat(String accountId, String newPassword) {
        clientService.resetPasswordAfterWechatVerify(accountId, newPassword);
    }

    public Map<String, Object> loginByPassword(String phone, String password) {
        List<ClientAccount> all = clientService.findByPhone(phone);
        List<ClientAccount> active = new ArrayList<>();
        for (ClientAccount c : all) {
            if (c.isActive()) {
                active.add(c);
            }
        }
        if (active.isEmpty()) {
            throw new BizException(403, "该客户账号已停用，请联系使用单位管理员");
        }
        List<ClientAccount> matched = new ArrayList<>();
        for (ClientAccount c : active) {
            if (password != null && encoder.matches(password, c.passwordHash)) {
                matched.add(c);
            }
        }
        if (matched.isEmpty()) {
            throw new BizException(401, "账号或密码错误");
        }
        return resolveLogin(phone, matched, null);
    }

    /** 微信登录：openid 只由服务端 code 换取后传入；命中活跃客户账号直接签发，多单位给候选 */
    public Map<String, Object> loginByOpenid(String openid) {
        List<ClientAccount> matched = new ArrayList<>();
        for (ClientAccount c : clientService.findByOpenid(openid)) {
            if (c.isActive()) {
                matched.add(c);
            }
        }
        if (matched.isEmpty()) {
            throw new BizException(422, "该微信尚未绑定客户账号，请联系使用单位管理员");
        }
        return resolveLogin(matched.get(0).phone, matched, openid);
    }

    /** 选单位：selectionToken 已验证手机号，此处只校验该单位下的活跃账号并签发正式令牌 */
    public Map<String, Object> selectUnit(String phone, String useUnitId) {
        for (ClientAccount c : clientService.findByPhone(phone)) {
            if (c.isActive() && useUnitId.equals(c.useUnitId)) {
                return issueToken(c, null);
            }
        }
        throw new BizException(1403, "该单位下无此客户账号");
    }

    /** 单一单位直接签发；多单位返回 needSelectUnit + selectionToken + 候选（口令已验才到这里） */
    private Map<String, Object> resolveLogin(String phone, List<ClientAccount> matched, String openid) {
        if (matched.size() == 1) {
            return issueToken(matched.get(0), openid);
        }
        List<Map<String, Object>> units = new ArrayList<>();
        for (ClientAccount c : matched) {
            UseUnit u = useUnitMapper.selectById(c.useUnitId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("useUnitId", c.useUnitId);
            item.put("unitName", u == null ? "" : u.unitName);
            item.put("accountId", c.id);
            item.put("role", c.roleCode);
            units.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        out.put("needSelectUnit", true);
        out.put("selectionToken", jwtService.issueSelection(phone));
        out.put("units", units);
        return out;
    }

    /** 注销会话：清 sid + 递增 scope_version，旧客户令牌立刻 401（幂等）。 */
    public void revokeSession(String accountId) {
        clientService.revokeSession(accountId);
    }

    public Map<String, Object> issueToken(ClientAccount c, String openid) {
        String sid = Ids.next("csess");
        clientService.issueSession(c.id, sid);
        int sv = c.scopeVersion == null ? 1 : c.scopeVersion;
        UseUnit unit = useUnitMapper.selectById(c.useUnitId);
        String unitName = unit == null || unit.unitName == null ? "" : unit.unitName;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwtService.issueClient(c.id, c.roleCode, c.useUnitId, openid, sid, sv));
        out.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        out.put("role", c.roleCode);
        out.put("roles", List.of(c.roleCode));
        out.put("useUnitId", c.useUnitId);
        out.put("needSelectUnit", false);
        out.put("perms", CLIENT_PERMS);
        out.put("home", CLIENT_HOME);
        out.put("activeWorkspace", "CLIENT");
        out.put("scope", unitName.isEmpty() ? "本单位" : unitName + "·本单位");
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", c.id);
        userInfo.put("name", c.name);
        userInfo.put("phone", c.phone);
        userInfo.put("role", c.roleCode);
        userInfo.put("unitName", unitName);
        userInfo.put("useUnitId", c.useUnitId);
        out.put("userInfo", userInfo);
        return out;
    }
}
