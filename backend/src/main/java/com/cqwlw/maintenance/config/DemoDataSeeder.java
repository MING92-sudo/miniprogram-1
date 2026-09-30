package com.cqwlw.maintenance.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.*;
import com.cqwlw.maintenance.mapper.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 首次启动填充最小演示数据；生产部署可通过 DB_SEED_DEMO=false 关闭。 */
@Component
public class DemoDataSeeder implements CommandLineRunner {
    private final CompanyMapper companyMapper;
    private final EmployeeMapper employeeMapper;
    private final UseUnitMapper useUnitMapper;
    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper workOrderMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(CompanyMapper companyMapper, EmployeeMapper employeeMapper, UseUnitMapper useUnitMapper,
                          ElevatorMapper elevatorMapper, WorkOrderMapper workOrderMapper, PasswordEncoder passwordEncoder) {
        this.companyMapper = companyMapper;
        this.employeeMapper = employeeMapper;
        this.useUnitMapper = useUnitMapper;
        this.elevatorMapper = elevatorMapper;
        this.workOrderMapper = workOrderMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        String seed = System.getenv().getOrDefault("DB_SEED_DEMO", "true");
        if (!seed.equalsIgnoreCase("true")) {
            return;
        }
        if (companyMapper.selectCount(null) == 0) {
            Company company = new Company();
            company.setOrganizationCode("DEMO-ORG-CODE");
            company.setName("演示维保单位");
            company.setWorkManagerName("赵敏");
            company.setWorkManagerPhone("13700000000");
            companyMapper.insert(company);
        }
        if (employeeMapper.selectCount(null) == 0) {
            List<String[]> people = List.of(
                    new String[]{"13800000001", "张伟", "WORKER", "维保人员", "6901282369105174537"},
                    new String[]{"13800000002", "陈刚", "LEADER", "班组长", "990002"},
                    new String[]{"13800000004", "李强", "WORKER", "维保人员", "6901284774286860288"}
            );
            for (String[] p : people) {
                Employee employee = new Employee();
                employee.setPhone(p[0]);
                employee.setPasswordHash(passwordEncoder.encode("123456"));
                employee.setName(p[1]);
                employee.setRole(p[2]);
                employee.setRoleText(p[3]);
                employee.setPlatformId(p[4]);
                employee.setActive(true);
                employee.setCreatedAt(LocalDateTime.now());
                employeeMapper.insert(employee);
            }
        }
        if (useUnitMapper.selectCount(null) == 0) {
            UseUnit unit = new UseUnit();
            unit.setUnitName("演示使用单位");
            unit.setUnitPrincipal("刘建国");
            unit.setUnitPrincipalPhone("13900000001");
            unit.setElevatorAdminister("王芳");
            unit.setElevatorAdministerPhone("13800000003");
            unit.setEmergencyPhone("023-60000000");
            unit.setCreatedAt(LocalDateTime.now());
            useUnitMapper.insert(unit);
        }
        if (elevatorMapper.selectCount(null) == 0) {
            Elevator elevator = new Elevator();
            elevator.setElevatorCode("EM-DEMO-001");
            elevator.setElevatorName("演示客梯");
            elevator.setDeviceCode("DT-DEMO-001");
            elevator.setRegistrationCode("TSCQ-DEMO-001");
            elevator.setInsideNumber("KT-01");
            elevator.setUseUnitId(1L);
            elevator.setCategory("曳引驱动电梯");
            elevator.setLongitude(new BigDecimal("106.6335200"));
            elevator.setLatitude(new BigDecimal("29.7192100"));
            elevator.setBrand("演示品牌");
            elevator.setManufacturer("演示制造单位");
            elevator.setProductNo("DEMO-001");
            elevator.setCreatedAt(LocalDateTime.now());
            elevatorMapper.insert(elevator);
        }
        if (workOrderMapper.selectCount(null) == 0) {
            WorkOrder order = new WorkOrder();
            order.setOrderNo("WO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            order.setElevatorId(1L);
            order.setWorkType("半月维保");
            order.setWorkTypeCode("HM");
            order.setPlanTime(LocalDateTime.now().plusMinutes(30));
            order.setStatus("PENDING");
            order.setWorkerName("张伟");
            order.setWorkerPhone("13800000001");
            order.setWorkerPlatformId("6901282369105174537");
            order.setChecklistJson("[]");
            order.setCreatedAt(LocalDateTime.now());
            workOrderMapper.insert(order);
        }
    }
}
