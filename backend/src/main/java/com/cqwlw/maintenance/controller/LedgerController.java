package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.BizProject;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.entity.SysInvite;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.service.GrantService;
import com.cqwlw.maintenance.service.LedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class LedgerController {

    private final LedgerService ledger;
    private final GrantService grantService;
    private final SysGrantMapper grantMapper;

    public LedgerController(LedgerService ledger, GrantService grantService, SysGrantMapper grantMapper) {
        this.ledger = ledger;
        this.grantService = grantService;
        this.grantMapper = grantMapper;
    }

    private static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @GetMapping("/org")
    public ApiResponse<List<Map<String, Object>>> org(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.USER_VIEW);
        return ApiResponse.ok(ledger.orgTree());
    }

    @GetMapping("/org/{id}/members")
    public ApiResponse<List<Map<String, Object>>> orgMembers(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.USER_VIEW);
        return ApiResponse.ok(ledger.orgMembers(id));
    }

    @PostMapping("/org")
    public ApiResponse<SysOrg> createOrg(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ROLE_ASSIGN);
        return ApiResponse.ok(ledger.createOrg(body, actor(req)));
    }

    @PutMapping("/org/{id}")
    public ApiResponse<SysOrg> updateOrg(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ROLE_ASSIGN);
        return ApiResponse.ok(ledger.updateOrg(id, body, actor(req)));
    }

    @GetMapping("/projects")
    public ApiResponse<List<BizProject>> projects(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.PROJECT_VIEW);
        return ApiResponse.ok(ledger.projects());
    }

    @PostMapping("/projects")
    public ApiResponse<BizProject> createProject(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.PROJECT_EDIT);
        return ApiResponse.ok(ledger.createProject(body, actor(req)));
    }

    @PutMapping("/projects/{id}")
    public ApiResponse<BizProject> updateProject(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.PROJECT_EDIT);
        return ApiResponse.ok(ledger.updateProject(id, body, actor(req)));
    }

    @GetMapping("/invites")
    public ApiResponse<List<SysInvite>> invites(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.INVITE_MANAGE);
        return ApiResponse.ok(ledger.invites());
    }

    @PostMapping("/invites")
    public ApiResponse<SysInvite> createInvite(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.INVITE_MANAGE);
        return ApiResponse.ok(ledger.createInvite(body, actor(req)));
    }

    @PostMapping("/invites/{id}/withdraw")
    public ApiResponse<SysInvite> withdrawInvite(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.INVITE_MANAGE);
        return ApiResponse.ok(ledger.withdrawInvite(id, actor(req)));
    }

    @GetMapping("/grants")
    public ApiResponse<List<SysGrant>> grants(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.INVITE_MANAGE);
        return ApiResponse.ok(grantMapper.selectList(new LambdaQueryWrapper<SysGrant>().orderByDesc(SysGrant::getCreatedAt)));
    }

    @PostMapping("/grants")
    public ApiResponse<SysGrant> createGrant(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ROLE_ASSIGN);
        // 授权必须有起止时间：缺字段/格式错按业务 422 返回，不能 String.valueOf(null) 抛解析异常（500）
        LocalDateTime start = requireDateTime(body, "startAt", "授权生效时间");
        LocalDateTime end = requireDateTime(body, "endAt", "授权失效时间");
        if (!end.isAfter(start)) {
            throw new com.cqwlw.maintenance.common.BizException(422, "授权失效时间必须晚于生效时间");
        }
        return ApiResponse.ok(grantService.create(str(body, "granteeId"), str(body, "permCode"),
                str(body, "scope"), str(body, "scopeRef"), start, end, actor(req), str(body, "reason")));
    }

    /** 时间参数必填且可解析（yyyy-MM-dd HH:mm:ss）；缺失或格式错统一 422 */
    private static LocalDateTime requireDateTime(Map<String, Object> body, String key, String label) {
        String raw = str(body, key);
        if (raw == null || raw.isBlank()) {
            throw new com.cqwlw.maintenance.common.BizException(422, label + "必填");
        }
        try {
            return LocalDateTime.parse(raw.trim().replace(' ', 'T'));
        } catch (RuntimeException e) {
            throw new com.cqwlw.maintenance.common.BizException(422, label + "格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    @PostMapping("/grants/{id}/withdraw")
    public ApiResponse<Map<String, Object>> withdrawGrant(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ROLE_ASSIGN);
        grantService.withdraw(id, actor(req));
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/elevator-changes")
    public ApiResponse<List<BizElevatorChange>> changes(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.ELEVATOR_VIEW);
        return ApiResponse.ok(ledger.changes());
    }

    @PostMapping("/elevator-changes")
    public ApiResponse<BizElevatorChange> initiateChange(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ELEVATOR_TRANSFER);
        return ApiResponse.ok(ledger.initiateChange(body, actor(req)));
    }

    @PostMapping("/elevator-changes/{id}/confirm")
    public ApiResponse<BizElevatorChange> confirmChange(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ELEVATOR_TRANSFER);
        return ApiResponse.ok(ledger.confirmChange(id, actor(req)));
    }

    @GetMapping("/roles-matrix")
    public ApiResponse<List<Map<String, Object>>> rolesMatrix(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.ROLE_ASSIGN);
        List<String> roles = new ArrayList<>(RoleCatalog.canonicalCodes());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : PermissionMatrix.canonicalCapabilities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            Map<String, String> states = new LinkedHashMap<>();
            for (String role : roles) {
                PermissionMatrix.TriState s = PermissionMatrix.state(role, code);
                states.put(role, s == PermissionMatrix.TriState.Y ? "Y" : s == PermissionMatrix.TriState.C ? "C" : "-");
            }
            row.put("states", states);
            rows.add(row);
        }
        return ApiResponse.ok(rows);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}