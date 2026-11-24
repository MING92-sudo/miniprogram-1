package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** - 绑定微信：邀请码 + wx.login code（openid 只由服务端 jscode2session 换取，AGENTS §2.3）。 */
@Service
public class UnitAccountService {

    private static final Set<String> PROVISION_ROLES = Set.of("ADMIN", "SYS_ADMIN", "MAINT_MANAGER");
    /** 邀请码字符集：去掉 0/O/1/I 等易混字符，方便电话口述 */
    private static final String INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String PASSWORD_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UseUnitMapper useUnitMapper;
    private final JwtService jwtService;
    private final WxAuthService wxAuthService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UnitAccountService(UseUnitMapper useUnitMapper, JwtService jwtService, WxAuthService wxAuthService) {
        this.useUnitMapper = useUnitMapper;
        this.jwtService = jwtService;
        this.wxAuthService = wxAuthService;
    }

    /** 开通/重置使用单位账号：返回一次性明文初始口令与邀请码（之后只存哈希）。 */
    public Map<String, Object> createAccount(String actorRole, String useUnitId,
                                             String account, String password) {
        if (!PROVISION_ROLES.contains(actorRole)) {
            throw new BizException(403, "仅平台管理员/维保经理可开通使用单位账号");
        }
        UseUnit unit = useUnitId == null ? null : useUnitMapper.selectById(useUnitId);
        if (unit == null) {
            throw new BizException(1404, "使用单位不存在");
        }
        String acc = account == null || account.isBlank() ? "U" + String.format("%08d",
                Math.abs(RANDOM.nextInt(100_000_000))) : account.trim();
        Long dup = useUnitMapper.selectCount(new LambdaQueryWrapper<UseUnit>()
                .eq(UseUnit::getAccount, acc)
                .ne(UseUnit::getId, unit.id));
        if (dup != null && dup > 0) {
            throw new BizException(422, "账号已被占用：" + acc);
        }
        if (password != null && !password.isBlank() && password.length() < 8) {
            throw new BizException(422, "初始密码至少 8 位");
        }
        String initialPassword = password == null || password.isBlank() ? randomPassword(12) : password;
        unit.account = acc;
        unit.passwordHash = encoder.encode(initialPassword);
        unit.inviteCode = randomInviteCode();
        useUnitMapper.updateById(unit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("useUnitId", unit.id);
        out.put("unitName", unit.unitName);
        out.put("account", unit.account);
        out.put("initialPassword", initialPassword);
        out.put("inviteCode", unit.inviteCode);
        return out;
    }

    @Deprecated
    public Map<String, Object> login(String account, String password) {
        String acc = account == null ? "" : account.trim();
        UseUnit unit = acc.isEmpty() ? null : useUnitMapper.selectOne(new LambdaQueryWrapper<UseUnit>()
                .eq(UseUnit::getAccount, acc).last("LIMIT 1"));
        if (unit == null || unit.passwordHash == null
                || password == null || !encoder.matches(password, unit.passwordHash)) {
            throw new BizException(401, "账号或密码错误");
        }
        requireNotMigrated(unit);
        return sessionOf(unit);
    }

    private static void requireNotMigrated(UseUnit unit) {
        if (unit.accountMigrated != null && unit.accountMigrated == 1) {
            throw new BizException(422, "该账号已并入统一登录，请使用手机号在登录页登录");
        }
    }

    /** 微信免密登录（已绑定的使用单位）：wx.login code → 服务端换 openid → 反查 use_unit。 */
    @Deprecated
    public Map<String, Object> loginByWechat(String wxCode) {
        if (wxCode == null || wxCode.isBlank()) {
            throw new BizException(422, "缺少微信登录凭证，请重试");
        }
        String openid = wxAuthService.openidFromCode(wxCode);
        UseUnit unit = useUnitMapper.selectOne(new LambdaQueryWrapper<UseUnit>()
                .eq(UseUnit::getOpenid, openid).last("LIMIT 1"));
        if (unit == null) {
            throw new BizException(422, "该微信尚未绑定使用单位，请先用邀请码绑定或使用账号口令登录");
        }
        requireNotMigrated(unit);
        return sessionOf(unit);
    }

    /** 自助改口令（门户令牌）：旧口令校验通过才允许改，新口令 ≥6 位且不得与原口令相同 */
    public Map<String, Object> changePassword(String unitId, String oldPassword, String newPassword) {
        UseUnit unit = unitId == null ? null : useUnitMapper.selectById(unitId);
        if (unit == null || unit.passwordHash == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        if (oldPassword == null || oldPassword.isBlank()
                || !encoder.matches(oldPassword, unit.passwordHash)) {
            throw new BizException(422, "原口令不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException(422, "新口令至少 6 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(422, "新口令不能与原口令相同");
        }
        unit.passwordHash = encoder.encode(newPassword);
        useUnitMapper.updateById(unit);
        return Map.of("ok", true);
    }

    /** 门户会话返回体（账号登录与微信免密登录共用同一口径） */
    private Map<String, Object> sessionOf(UseUnit unit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwtService.issueUnit(unit.id, "mp"));
        out.put("account", unit.account);
        out.put("useUnitId", unit.id);
        out.put("unitName", unit.unitName);
        out.put("role", "UNIT_ADMIN");
        out.put("wechatBound", unit.openid != null && !unit.openid.isBlank());
        return out;
    }

    /** 邀请码绑定微信：openid 由服务端用 wx.login code 换取；重复绑定以最后一次为准 */
    public Map<String, Object> bindWechat(String inviteCode, String wxCode) {
        String code = inviteCode == null ? "" : inviteCode.trim();
        UseUnit unit = code.isEmpty() ? null : useUnitMapper.selectOne(new LambdaQueryWrapper<UseUnit>()
                .eq(UseUnit::getInviteCode, code).last("LIMIT 1"));
        if (unit == null) {
            throw new BizException(422, "邀请码无效，请联系维保单位重新获取");
        }
        String openid = wxAuthService.openidFromCode(wxCode);
        unit.openid = openid;
        unit.boundAt = TimeUtil.now();
        useUnitMapper.updateById(unit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("useUnitId", unit.id);
        out.put("unitName", unit.unitName);
        out.put("bound", true);
        return out;
    }

    private static String randomPassword(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String randomInviteCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(INVITE_ALPHABET.charAt(RANDOM.nextInt(INVITE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
