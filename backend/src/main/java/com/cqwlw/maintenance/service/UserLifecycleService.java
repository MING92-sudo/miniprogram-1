package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 离职转交：单事务内把未完成工单/急修单迁移给同区域在职接手人并吊销目标会话。 */
@Service
public class UserLifecycleService {

    private final EmployeeMapper employeeMapper;
    private final WorkOrderMapper orderMapper;
    private final FaultMapper faultMapper;
    private final WorkOrderService workOrderService;
    private final TransactionTemplate transactionTemplate;

    public UserLifecycleService(EmployeeMapper employeeMapper, WorkOrderMapper orderMapper,
                                FaultMapper faultMapper, WorkOrderService workOrderService,
                                TransactionTemplate transactionTemplate) {
        this.employeeMapper = employeeMapper;
        this.orderMapper = orderMapper;
        this.faultMapper = faultMapper;
        this.workOrderService = workOrderService;
        this.transactionTemplate = transactionTemplate;
    }

    public Map<String, Object> resign(String actorId, String targetId, String heirId) {
        return transactionTemplate.execute(status -> doResign(actorId, targetId, heirId));
    }

    private Map<String, Object> doResign(String actorId, String targetId, String heirId) {
        Employee target = lockEmployee(targetId);
        if (target == null) {
            throw new BizException(404, "账号不存在");
        }
        if (isBlank(heirId)) {
            throw new BizException(422, "请选择接手人");
        }
        if (heirId.equals(targetId)) {
            throw new BizException(422, "接手人不能为本人");
        }
        Employee heir = lockEmployee(heirId);
        if (heir == null) {
            throw new BizException(422, "接手人不存在");
        }
        if (Boolean.FALSE.equals(heir.enabled)) {
            throw new BizException(422, "接手人账号已停用，不能接手");
        }
        if (isBlank(heir.platformId)) {
            throw new BizException(422, "接手人未完成平台人员登记，不能接手工单");
        }
        if (!Objects.equals(nz(target.regionId), nz(heir.regionId))) {
            throw new BizException(422, "接手人所属区域与离职人员不一致");
        }

        int orderCount = migrateOrders(target, heir, actorId);
        int faultCount = migrateFaults(target, heir);

        target.enabled = false;
        employeeMapper.updateById(target);
        employeeMapper.bumpScopeVersion(targetId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orderCount);
        result.put("faults", faultCount);
        result.put("total", orderCount + faultCount);
        return result;
    }

    private int migrateOrders(Employee target, Employee heir, String actorId) {
        if (isBlank(target.platformId)) {
            return 0;
        }
        List<WorkOrder> orders = orderMapper.selectInflightForUpdate(target.platformId);
        int count = 0;
        for (WorkOrder o : orders == null ? List.<WorkOrder>of() : orders) {
            if ("DONE".equals(o.status)) {
                continue;
            }
            if ("PROCESSING".equals(o.status)) {
                if (isBlank(o.regionId)) {
                    throw new BizException(422, "在途工单 " + nz(o.orderNo)
                            + " 缺少区域快照，无法安全退回池，离职转交已取消");
                }
                // 在途留痕只属于原作业人：复用退回池重置认领/签到/签名，绝不直接改派继承旧证据。
                workOrderService.releaseToPool(o.id, actorId);
                count++;
                continue;
            }
            boolean asPrincipal = target.platformId.equals(o.workerPlatformId);
            boolean asAssistant = target.platformId.equals(o.assistantPlatformId);
            if (asPrincipal && orderMapper.reassignWorker(
                    o.id, heir.name, heir.platformId, target.platformId) != 1) {
                throw new BizException(422, "工单 " + nz(o.orderNo) + " 状态已变化，请刷新后重试");
            }
            if (asAssistant && orderMapper.reassignAssistant(
                    o.id, heir.name, heir.platformId, target.platformId) != 1) {
                throw new BizException(422, "工单 " + nz(o.orderNo) + " 状态已变化，请刷新后重试");
            }
            if (asPrincipal || asAssistant) {
                count++;
            }
        }
        return count;
    }

    private int migrateFaults(Employee target, Employee heir) {
        List<Fault> faults = faultMapper.selectUnclosedForUpdate(target.id);
        int count = 0;
        for (Fault f : faults == null ? List.<Fault>of() : faults) {
            if ("CLOSED".equals(f.status) || "CANCELLED".equals(f.status)) {
                continue;
            }
            if ("PROCESSING".equals(f.status)) {
                throw new BizException(422, "急修单 " + nz(f.faultNo)
                        + " 处理中，无安全迁移规则，离职转交已取消");
            }
            if (faultMapper.reassignDispatchWorker(f.id, heir.id) != 1) {
                throw new BizException(422, "急修单 " + nz(f.faultNo) + " 状态已变化，请刷新后重试");
            }
            count++;
        }
        return count;
    }

    private Employee lockEmployee(String id) {
        if (isBlank(id)) {
            return null;
        }
        List<Employee> rows = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getId, id)
                .last("FOR UPDATE"));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
