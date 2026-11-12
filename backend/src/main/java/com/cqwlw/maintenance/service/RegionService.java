package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Region;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.RegionMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 区域主数据 + 电梯/员工区域分配（Task 1，移交/外接建档/使用单位门户的可见性范围谓词）。
 * 管理端门禁在 RegionController 校验（非管理员 1403，本服务不做角色判断）。
 */
@Service
public class RegionService {

    private final RegionMapper regionMapper;
    private final ElevatorMapper elevatorMapper;
    private final EmployeeMapper employeeMapper;

    public RegionService(RegionMapper regionMapper, ElevatorMapper elevatorMapper,
                         EmployeeMapper employeeMapper) {
        this.regionMapper = regionMapper;
        this.elevatorMapper = elevatorMapper;
        this.employeeMapper = employeeMapper;
    }

    public List<Region> list() {
        return regionMapper.selectList(new LambdaQueryWrapper<Region>()
                .orderByAsc(Region::getSortOrder).orderByAsc(Region::getId));
    }

    public Region create(Map<String, Object> body) {
        String name = str(body, "name");
        if (name == null || name.isBlank()) {
            throw new BizException(422, "区域名称不能为空");
        }
        Region r = new Region();
        r.id = Ids.next("rg");
        r.name = name.trim();
        r.sortOrder = intOrNull(body == null ? null : body.get("sortOrder"));
        r.remark = str(body, "remark");
        regionMapper.insert(r);
        return r;
    }

    public Region update(String id, Map<String, Object> body) {
        if (regionMapper.selectById(id) == null) {
            throw new BizException(1404, "区域不存在");
        }
        if (body == null) {
            body = Map.of();
        }
        LambdaUpdateWrapper<Region> uw = new LambdaUpdateWrapper<Region>().eq(Region::getId, id);
        boolean any = false;
        if (body.containsKey("name")) {
            String v = str(body, "name");
            if (v == null || v.isBlank()) {
                throw new BizException(422, "区域名称不能为空");
            }
            uw.set(Region::getName, v.trim());
            any = true;
        }
        if (body.containsKey("sortOrder")) {
            uw.set(Region::getSortOrder, intOrNull(body.get("sortOrder")));
            any = true;
        }
        if (body.containsKey("remark")) {
            uw.set(Region::getRemark, str(body, "remark"));
            any = true;
        }
        if (!any) {
            throw new BizException(422, "无可更新的区域信息");
        }
        regionMapper.update(null, uw);
        return regionMapper.selectById(id);
    }

    public void delete(String id) {
        if (regionMapper.selectById(id) == null) {
            throw new BizException(1404, "区域不存在");
        }
        regionMapper.deleteById(id);
    }

    /** 电梯分配区域（regionId 必填且须存在，写前校验，不落脏数据） */
    public void assignElevatorRegion(String elevatorId, String regionId) {
        if (elevatorMapper.selectById(elevatorId) == null) {
            throw new BizException(1404, "电梯不存在");
        }
        String rid = requireRegion(regionId);
        Elevator patch = new Elevator();
        patch.id = elevatorId;
        patch.regionId = rid;
        elevatorMapper.updateById(patch);
    }

    /** 员工分配区域（regionId 必填且须存在，写前校验，不落脏数据） */
    public void assignEmployeeRegion(String employeeId, String regionId) {
        if (employeeMapper.selectById(employeeId) == null) {
            throw new BizException(1404, "员工不存在");
        }
        String rid = requireRegion(regionId);
        Employee patch = new Employee();
        patch.id = employeeId;
        patch.regionId = rid;
        employeeMapper.updateById(patch);
    }

    private String requireRegion(String regionId) {
        String rid = regionId == null ? "" : regionId.trim();
        if (rid.isEmpty()) {
            throw new BizException(422, "请选择区域");
        }
        if (regionMapper.selectById(rid) == null) {
            throw new BizException(1404, "区域不存在");
        }
        return rid;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
