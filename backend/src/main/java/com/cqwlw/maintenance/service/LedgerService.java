package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.BizElevatorChange;
import com.cqwlw.maintenance.entity.BizProject;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysInvite;
import com.cqwlw.maintenance.entity.SysOrg;
import com.cqwlw.maintenance.mapper.BizElevatorChangeMapper;
import com.cqwlw.maintenance.mapper.BizProjectMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import com.cqwlw.maintenance.mapper.SysInviteMapper;
import com.cqwlw.maintenance.mapper.SysOrgMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 电梯变更确认人必须不同于发起人。 */
@Service
public class LedgerService {

    /** 组织树只允许这三层：公司（顶级且唯一）→ 部门 → 班组 */
    private static final java.util.Set<String> ORG_TYPES = java.util.Set.of("COMPANY", "DEPT", "TEAM");

    private final SysOrgMapper orgMapper;
    private final BizProjectMapper projectMapper;
    private final SysInviteMapper inviteMapper;
    private final BizElevatorChangeMapper changeMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final RegionMapper regionMapper;
    private final AuditLogService audit;

    public LedgerService(SysOrgMapper orgMapper, BizProjectMapper projectMapper,
                         SysInviteMapper inviteMapper, BizElevatorChangeMapper changeMapper,
                         ElevatorMapper elevatorMapper, EmployeeMapper employeeMapper,
                         RegionMapper regionMapper, AuditLogService audit) {
        this.orgMapper = orgMapper;
        this.projectMapper = projectMapper;
        this.inviteMapper = inviteMapper;
        this.changeMapper = changeMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.regionMapper = regionMapper;
        this.audit = audit;
    }

    /** 组织树（GET /org）：只渲染内部组织（公司 → 部门 → 班组）。 */
    public List<SysOrg> orgTree() {
        List<SysOrg> out = new ArrayList<>();
        for (SysOrg o : orgMapper.selectList(new LambdaQueryWrapper<SysOrg>().orderByAsc(SysOrg::getSortOrder))) {
            if (!"UNIT".equals(o.type)) {
                out.add(o);
            }
        }
        return out;
    }

    /** 只读，不写审计。 */
    public List<Map<String, Object>> orgMembers(String orgId) {
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(1404, "组织不存在");
        }
        String type = org.type == null ? "" : org.type;
        String deptCode = "DEPT".equals(type) ? deptCodeOf(org.name) : null;
        if ("UNIT".equals(type) || ("DEPT".equals(type) && deptCode == null)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<Employee>().orderByAsc(Employee::getId))) {
            boolean match = switch (type) {
                case "TEAM" -> org.name != null && org.name.equals(e.groupName);
                case "DEPT" -> deptCode.equals(e.department);
                case "COMPANY" -> "MAINT".equals(e.department) || "INSTALL".equals(e.department);
                default -> false;
            };
            if (match) {
                out.add(memberRow(e));
            }
        }
        return out;
    }

    /** DEPT 节点名称 → 部门码；无匹配规则返回 null（视为无成员） */
    private static String deptCodeOf(String name) {
        if (name == null) {
            return null;
        }
        if (name.contains("维保")) {
            return "MAINT";
        }
        if (name.contains("安装")) {
            return "INSTALL";
        }
        if (name.contains("平台")) {
            return "PLATFORM";
        }
        return null;
    }

    private Map<String, Object> memberRow(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("name", nz(e.name));
        m.put("phone", nz(e.phone));
        m.put("role", nz(e.role));
        m.put("roleText", nz(e.roleText));
        m.put("groupName", nz(e.groupName));
        m.put("regionId", nz(e.regionId));
        m.put("regionName", regionNameOf(e.regionId));
        m.put("enabled", !Boolean.FALSE.equals(e.enabled));
        return m;
    }

    /** 区域名称（成员行展示用）；未配置区域或查不到时返回空串 */
    private String regionNameOf(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return "";
        }
        Region r = regionMapper.selectById(regionId);
        return r == null ? "" : nz(r.name);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public SysOrg createOrg(Map<String, Object> body, String actor) {
        String type = str(body, "type");
        String name = str(body, "name");
        if (type == null || name == null || name.isBlank()) {
            throw new BizException(422, "组织类型与名称必填");
        }
        // 使用单位是外部客户主体（use_unit / client_account），不进内部组织树
        if ("UNIT".equals(type)) {
            throw new BizException(422, "使用单位为外部客户主体，请在「使用单位档案」中开通");
        }
        if (!ORG_TYPES.contains(type)) {
            throw new BizException(422, "组织类型仅支持 公司/部门/班组");
        }
        String parentId = str(body, "parentId");
        SysOrg parent = null;
        if (parentId != null) {
            parent = orgMapper.selectById(parentId);
            if (parent == null) {
                throw new BizException(422, "上级组织不存在");
            }
            if ("UNIT".equals(parent.getType())) {
                throw new BizException(422, "使用单位节点为单级，禁止挂子节点");
            }
        }
        requireValidParent(type, parentId, parent);
        SysOrg org = new SysOrg();
        org.id = Ids.next("org");
        org.type = type;
        org.name = name;
        org.code = str(body, "code");
        org.parentId = parentId;
        org.leaderId = str(body, "leaderId");
        org.status = "ACTIVE";
        org.sortOrder = body.get("sortOrder") == null ? 0 : Integer.parseInt(String.valueOf(body.get("sortOrder")));
        org.createdAt = TimeUtil.now();
        orgMapper.insert(org);
        audit(actor, "org:create", org.id, "SUCCESS", null);
        return org;
    }

    private void requireValidParent(String type, String parentId, SysOrg parent) {
        if ("COMPANY".equals(type)) {
            if (parentId != null) {
                throw new BizException(422, "公司为顶级组织，不能指定上级");
            }
            Long companies = orgMapper.selectCount(
                    new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getType, "COMPANY"));
            if (companies != null && companies > 0) {
                throw new BizException(422, "已存在公司节点，单公司模型不支持新增公司");
            }
            return;
        }
        if (parentId == null) {
            throw new BizException(422, "部门与班组必须归属上级：部门挂公司下，班组挂部门下");
        }
        if ("DEPT".equals(type) && !"COMPANY".equals(parent.getType())) {
            throw new BizException(422, "部门只能挂在公司下");
        }
        if ("TEAM".equals(type) && !"DEPT".equals(parent.getType())) {
            throw new BizException(422, "班组只能挂在部门下");
        }
    }

    public SysOrg updateOrg(String id, Map<String, Object> body, String actor) {
        SysOrg org = orgMapper.selectById(id);
        if (org == null) {
            throw new BizException(404, "组织不存在");
        }
        if (body.containsKey("name")) org.name = str(body, "name");
        if (body.containsKey("leaderId")) org.leaderId = str(body, "leaderId");
        if (body.containsKey("status")) org.status = str(body, "status");
        orgMapper.updateById(org);
        audit(actor, "org:edit", id, "SUCCESS", null);
        return org;
    }

    public List<BizProject> projects() {
        return projectMapper.selectList(new LambdaQueryWrapper<BizProject>().orderByDesc(BizProject::getCreatedAt));
    }

    public BizProject createProject(Map<String, Object> body, String actor) {
        String no = str(body, "projectNo");
        String name = str(body, "name");
        if (no == null || name == null || name.isBlank()) {
            throw new BizException(422, "项目编号与名称必填");
        }
        BizProject p = new BizProject();
        p.id = Ids.next("proj");
        p.projectNo = no;
        p.name = name;
        p.useUnitId = str(body, "useUnitId");
        p.maintOrgId = requireDeptOrg(str(body, "maintOrgId"), "维保部门");
        p.installOrgId = requireDeptOrg(str(body, "installOrgId"), "安装部门");
        p.elevatorCount = body.get("elevatorCount") == null ? 0 : Integer.parseInt(String.valueOf(body.get("elevatorCount")));
        p.contractId = str(body, "contractId");
        p.contractEndDate = str(body, "contractEndDate");
        p.status = "ACTIVE";
        p.createdAt = TimeUtil.now();
        projectMapper.insert(p);
        audit(actor, "project:edit", p.id, "SUCCESS", null);
        return p;
    }

    /** 历史上这两列可存任意字符串，若存成公司/班组/使用单位，组织口径统计与派单归属都会错位。 */
    private String requireDeptOrg(String orgId, String label) {
        if (orgId == null) {
            return null;
        }
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(422, label + "不存在");
        }
        if (!"DEPT".equals(org.getType())) {
            throw new BizException(422, label + "只能选择部门节点");
        }
        return orgId;
    }

    public BizProject updateProject(String id, Map<String, Object> body, String actor) {
        BizProject p = projectMapper.selectById(id);
        if (p == null) {
            throw new BizException(404, "项目不存在");
        }
        if (body.containsKey("name")) p.name = str(body, "name");
        if (body.containsKey("status")) p.status = str(body, "status");
        if (body.containsKey("elevatorCount")) p.elevatorCount = Integer.parseInt(String.valueOf(body.get("elevatorCount")));
        projectMapper.updateById(p);
        audit(actor, "project:edit", id, "SUCCESS", null);
        return p;
    }

    public List<SysInvite> invites() {
        return inviteMapper.selectList(new LambdaQueryWrapper<SysInvite>().orderByDesc(SysInvite::getCreatedAt));
    }

    public SysInvite createInvite(Map<String, Object> body, String actor) {
        int days = body.get("validDays") == null ? 7 : Integer.parseInt(String.valueOf(body.get("validDays")));
        if (days < 1 || days > 30) {
            throw new BizException(422, "有效期须 1—30 天");
        }
        SysInvite inv = new SysInvite();
        inv.id = Ids.next("inv");
        inv.code = Ids.next("INV").toUpperCase();
        inv.type = str(body, "type") == null ? "INVITE" : str(body, "type");
        inv.targetOrgId = str(body, "targetOrgId");
        inv.createdBy = actor;
        inv.validDays = days;
        inv.expiresAt = TimeUtil.now().plusDays(days);
        inv.status = "ACTIVE";
        inv.joinedCount = 0;
        inv.createdAt = TimeUtil.now();
        inviteMapper.insert(inv);
        audit(actor, "invite:manage", inv.id, "SUCCESS", null);
        return inv;
    }

    public SysInvite withdrawInvite(String id, String actor) {
        SysInvite inv = inviteMapper.selectById(id);
        if (inv == null) {
            throw new BizException(404, "邀请不存在");
        }
        if (!"ACTIVE".equals(inv.status)) {
            throw new BizException(422, "仅有效态可撤回");
        }
        inv.status = "WITHDRAWN";
        inviteMapper.updateById(inv);
        audit(actor, "invite:manage", id, "SUCCESS", "撤回");
        return inv;
    }

    public List<BizElevatorChange> changes() {
        return changeMapper.selectList(new LambdaQueryWrapper<BizElevatorChange>().orderByDesc(BizElevatorChange::getCreatedAt));
    }

    public BizElevatorChange initiateChange(Map<String, Object> body, String actor) {
        String elevatorId = str(body, "elevatorId");
        String type = str(body, "changeType");
        String reason = str(body, "reason");
        if (elevatorId == null || type == null || reason == null || reason.isBlank()) {
            throw new BizException(422, "电梯/变更类型/原因必填");
        }
        if (elevatorMapper.selectById(elevatorId) == null) {
            throw new BizException(404, "电梯不存在");
        }
        BizElevatorChange c = new BizElevatorChange();
        c.id = Ids.next("chg");
        c.elevatorId = elevatorId;
        c.changeType = type;
        c.initiatorId = actor;
        c.reason = reason;
        c.status = "PENDING";
        c.createdAt = TimeUtil.now();
        changeMapper.insert(c);
        audit(actor, "elevator:transfer", c.id, "SUCCESS", "发起" + type);
        return c;
    }

    public BizElevatorChange confirmChange(String id, String confirmer, String actor) {
        BizElevatorChange c = changeMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "变更单不存在");
        }
        if (!"PENDING".equals(c.status)) {
            throw new BizException(422, "仅待确认可操作");
        }
        if (confirmer == null || confirmer.equals(c.initiatorId)) {
            throw new BizException(422, "确认人必须不同于发起人");
        }
        c.confirmerId = confirmer;
        c.status = "CONFIRMED";
        c.confirmedAt = TimeUtil.now();
        changeMapper.updateById(c);
        Elevator el = elevatorMapper.selectById(c.elevatorId);
        if (el != null) {
            el.status = switch (c.changeType) {
                case "SCRAP" -> "SCRAPPED";
                case "DISABLE" -> "INACTIVE";
                case "TRANSFER" -> "TRANSFER";
                default -> el.status;
            };
            elevatorMapper.updateById(el);
        }
        audit(actor, "elevator:transfer", id, "SUCCESS", "确认" + c.changeType);
        return c;
    }

    private void audit(String actor, String action, String target, String result, String reason) {
        SysAuditLog a = new SysAuditLog();
        a.actorId = actor;
        a.action = action;
        a.target = target;
        a.result = result;
        a.reason = reason;
        audit.append(a);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}