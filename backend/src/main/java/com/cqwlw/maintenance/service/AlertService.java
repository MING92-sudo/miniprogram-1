package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.AlertRecord;
import com.cqwlw.maintenance.entity.AlertRule;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.InspectRecord;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.AlertRecordMapper;
import com.cqwlw.maintenance.mapper.AlertRuleMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.InspectRecordMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预警规则与记录。可生成的预警：年检到期 / 人员证件到期 / 维保超期 / 使用单位确认超时 /
 * 自行检查未完成；合同、库存、演练超期缺数据模型，规则可配置但不生成。
 */
@Service
public class AlertService {

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper orderMapper;
    private final MaintainRecordMapper maintainRecordMapper;
    private final InspectRecordMapper inspectMapper;

    public AlertService(AlertRuleMapper ruleMapper, AlertRecordMapper recordMapper,
                        ElevatorMapper elevatorMapper, EmployeeMapper employeeMapper,
                        WorkOrderMapper orderMapper, MaintainRecordMapper maintainRecordMapper,
                        InspectRecordMapper inspectMapper) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
        this.orderMapper = orderMapper;
        this.maintainRecordMapper = maintainRecordMapper;
        this.inspectMapper = inspectMapper;
    }

    public List<Map<String, Object>> listRules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlertRule r : ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .orderByAsc(AlertRule::getId))) {
            list.add(ruleRow(r));
        }
        return list;
    }

    public Map<String, Object> updateRule(String id, Map<String, Object> body) {
        AlertRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "预警规则不存在");
        }
        if (body.get("advanceDays") != null) {
            Object v = body.get("advanceDays");
            try {
                r.advanceDays = Integer.valueOf(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                throw new BizException(422, "advanceDays 须为整数");
            }
        }
        if (body.get("enabled") != null) {
            r.enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        }
        r.updatedAt = TimeUtil.now();
        ruleMapper.updateById(r);
        return ruleRow(r);
    }

    public Map<String, Object> listAlerts(Map<String, String> q) {
        LambdaQueryWrapper<AlertRecord> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("ruleType"))) {
            w.eq(AlertRecord::getRuleType, q.get("ruleType"));
        }
        if (notBlank(q.get("status"))) {
            w.eq(AlertRecord::getStatus, q.get("status"));
        }
        w.orderByDesc(AlertRecord::getCreatedAt);
        List<AlertRecord> all = recordMapper.selectList(w);
        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlertRecord r : all.subList(from, to)) {
            list.add(recordRow(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** 按启用规则扫描生成预警（幂等：同规则同内容未关闭的不重复生成）；返回新增条数 */
    public Map<String, Object> generate() {
        LocalDate today = TimeUtil.now().toLocalDate();
        int created = 0;
        for (AlertRule rule : ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>()
                .eq(AlertRule::getEnabled, true))) {
            created += switch (rule.type) {
                case "YEAR_CHECK" -> yearCheckAlerts(rule, today);
                case "CERT_EXPIRE" -> certAlerts(rule, today);
                case "MAINT_OVERDUE" -> maintOverdueAlerts(rule, today);
                case "CONFIRM_TIMEOUT" -> confirmTimeoutAlerts(rule);
                case "SELF_INSPECT" -> selfInspectAlerts(rule, today);
                default -> 0; // 合同/库存/演练超期：数据模型后置
            };
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("generatedAt", TimeUtil.format(TimeUtil.now()));
        return out;
    }

    private int yearCheckAlerts(AlertRule rule, LocalDate today) {
        int n = 0;
        for (Elevator el : elevatorMapper.selectList(null)) {
            if (el.nextCheckDate == null) {
                continue;
            }
            long days = today.until(el.nextCheckDate).getDays();
            if (days >= 0 && days <= rule.advanceDays) {
                n += add(rule, "年检到期",
                        el.elevatorName + " 下次定期检验 " + TimeUtil.formatDate(el.nextCheckDate)
                                + "（剩余 " + days + " 天）");
            }
        }
        return n;
    }

    private int certAlerts(AlertRule rule, LocalDate today) {
        int n = 0;
        for (Employee e : employeeMapper.selectList(new LambdaQueryWrapper<>())) {
            LocalDate end = TimeUtil.parseDate(e.workEndDate);
            if (end == null) {
                continue;
            }
            long days = today.until(end).getDays();
            if (days >= 0 && days <= rule.advanceDays) {
                n += add(rule, "人员证件到期",
                        e.name + " 作业证件 " + TimeUtil.formatDate(end) + " 到期（剩余 " + days + " 天）");
            }
        }
        return n;
    }

    private int maintOverdueAlerts(AlertRule rule, LocalDate today) {
        int n = 0;
        for (WorkOrder o : orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getStatus, "PENDING", "PROCESSING"))) {
            if (o.planTime == null || o.planTime.toLocalDate().isAfter(today)) {
                continue;
            }
            n += add(rule, "维保超期",
                    (o.orderNo == null ? o.id : o.orderNo) + " 计划 " + TimeUtil.date(o.planTime)
                            + " 已逾期未完成");
        }
        return n;
    }

    private int confirmTimeoutAlerts(AlertRule rule) {
        int n = 0;
        for (MaintainRecord r : maintainRecordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getConfirmStatus, "PENDING"))) {
            if (r.createdAt == null || r.createdAt.isAfter(TimeUtil.now().minusHours(24))) {
                continue;
            }
            n += add(rule, "使用单位确认超时",
                    (r.elevatorName == null ? r.elevatorCode : r.elevatorName)
                            + " 维保记录签退超 24 小时未确认");
        }
        return n;
    }

    private int selfInspectAlerts(AlertRule rule, LocalDate today) {
        int n = 0;
        for (Elevator el : elevatorMapper.selectList(null)) {
            if (el.nextCheckDate == null) {
                continue;
            }
            long days = today.until(el.nextCheckDate).getDays();
            if (days < 0 || days > rule.advanceDays) {
                continue;
            }
            Long done = inspectMapper.selectCount(new LambdaQueryWrapper<InspectRecord>()
                    .eq(InspectRecord::getElevatorId, el.id));
            if (done != null && done > 0) {
                continue;
            }
            n += add(rule, "自行检查未完成",
                    el.elevatorName + " 距定期检验 " + days + " 天，尚无年度自行检查记录");
        }
        return n;
    }

    private int add(AlertRule rule, String title, String content) {
        Long dup = recordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleType, rule.type)
                .eq(AlertRecord::getContent, content)
                .eq(AlertRecord::getStatus, "OPEN"));
        if (dup != null && dup > 0) {
            return 0;
        }
        AlertRecord r = new AlertRecord();
        r.id = Ids.next("al");
        r.ruleType = rule.type;
        r.title = title;
        r.content = content;
        r.target = rule.target;
        r.status = "OPEN";
        r.readFlag = false;
        r.createdAt = TimeUtil.now();
        recordMapper.insert(r);
        return 1;
    }

    private Map<String, Object> ruleRow(AlertRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("type", r.type);
        m.put("name", r.name);
        m.put("advanceDays", r.advanceDays);
        m.put("target", r.target);
        m.put("enabled", Boolean.TRUE.equals(r.enabled));
        return m;
    }

    private Map<String, Object> recordRow(AlertRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("ruleType", r.ruleType);
        m.put("title", r.title);
        m.put("content", r.content);
        m.put("target", r.target);
        m.put("status", r.status);
        m.put("readFlag", Boolean.TRUE.equals(r.readFlag));
        m.put("createdAt", TimeUtil.format(r.createdAt));
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
