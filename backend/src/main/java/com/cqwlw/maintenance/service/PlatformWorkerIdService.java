package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 关键约束：- 匹配键必须是 workManCertificate（证书号平台内唯一），严禁用姓名匹配（会重名）； */
@Service
public class PlatformWorkerIdService {

    private static final Logger log = LoggerFactory.getLogger(PlatformWorkerIdService.class);

    public static final String SYNCED = "SYNCED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String NO_CERT = "NO_CERT";
    public static final String DISABLED = "DISABLED";

    /** 立即查失败后的退避重试间隔（秒），累计约 5 分钟。 */
    private static final long[] RETRY_SECONDS = {5, 15, 60, 60, 60, 60, 40};

    private final PlatformClient platformClient;
    private final PlatformTokenService tokenService;
    private final EmployeeMapper employeeMapper;

    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "worker-platform-id-backfill");
        t.setDaemon(true);
        return t;
    });
    /** 同一证书号只保留一个在途重试链，避免重复建档投递多条任务。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public PlatformWorkerIdService(PlatformClient platformClient, PlatformTokenService tokenService,
                                   EmployeeMapper employeeMapper) {
        this.platformClient = platformClient;
        this.tokenService = tokenService;
        this.employeeMapper = employeeMapper;
    }

    /** 本次建档人未命中则退避重试。 */
    public Map<String, Object> backfillWithRetry(String certificate) {
        BackfillOutcome outcome = backfillAll();
        String selfStatus;
        if (outcome.conflictCertificates.contains(certificate)) {
            selfStatus = AMBIGUOUS;
        } else if (DISABLED.equals(outcome.status)) {
            selfStatus = DISABLED;
        } else {
            selfStatus = outcome.syncedCertificates.contains(certificate) ? SYNCED : NOT_FOUND;
        }
        if (terminal(selfStatus) || !inFlight.add(certificate)) {
            return outcome.asResult(selfStatus);
        }
        scheduleRetry(certificate, 0);
        return outcome.asResult(selfStatus);
    }

    /** 全量回填（手动兜底端点用）：一次 2.5 查询，回填所有本地有证书人员。 */
    public Map<String, Object> backfillAllFromPlatform() {
        return backfillAll().asResult(null);
    }

    private static boolean terminal(String r) {
        return SYNCED.equals(r) || AMBIGUOUS.equals(r) || NO_CERT.equals(r) || DISABLED.equals(r);
    }

    private void scheduleRetry(String certificate, int attempt) {
        if (attempt >= RETRY_SECONDS.length) {
            inFlight.remove(certificate);
            log.warn("人员平台ID回填重试达上限（约5分钟）仍未命中，需人工处理: certificate={}", certificate);
            return;
        }
        retryExecutor.schedule(() -> {
            try {
                BackfillOutcome outcome = backfillAll();
                if (outcome.syncedCertificates.contains(certificate) || DISABLED.equals(outcome.status)) {
                    inFlight.remove(certificate);
                    return;
                }
            } catch (Exception e) {
                log.warn("人员平台ID回填重试异常: certificate={}, {}", certificate, e.getMessage());
            }
            scheduleRetry(certificate, attempt + 1);
        }, RETRY_SECONDS[attempt], TimeUnit.SECONDS);
    }

    /** 调 2.5 全量查询并按证书号回填所有本地有证书人员（证书号重复不自动取首条）。 */
    private BackfillOutcome backfillAll() {
        Map<String, Object> stats = new LinkedHashMap<>();
        Set<String> syncedCertificates = new java.util.HashSet<>();
        if (!tokenService.configured()) {
            stats.put("platformWorkers", 0);
            stats.put("scanned", 0);
            stats.put("synced", 0);
            stats.put("notFound", 0);
            stats.put("ambiguous", 0);
            stats.put("noCertificate", 0);
            stats.put("disabled", 1);
            stats.put("conflictCertificates", List.of());
            stats.put("backfilledAt", TimeUtil.format(TimeUtil.now()));
            return new BackfillOutcome(stats, syncedCertificates, Set.of(), DISABLED);
        }
        String workEndDate = TimeUtil.date(TimeUtil.now().plusYears(1));
        List<Map<String, Object>> workers = platformClient.queryWorkList("0", workEndDate);
        // 按证书号分组；id 缺失的条目忽略
        Map<String, List<Object>> byCert = new LinkedHashMap<>();
        for (Map<String, Object> w : workers) {
            String cert = str(w.get("workManCertificate"));
            Object id = w.get("id");
            if (cert == null || cert.isBlank() || id == null || str(id).isBlank()) {
                continue;
            }
            byCert.computeIfAbsent(cert, k -> new java.util.ArrayList<>()).add(id);
        }
        List<Employee> locals = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .isNotNull(Employee::getCertificate).ne(Employee::getCertificate, ""));
        Map<String, Integer> localCertificateCounts = new java.util.HashMap<>();
        for (Employee e : locals) {
            localCertificateCounts.merge(e.certificate, 1, Integer::sum);
        }
        Set<String> localConflicts = new java.util.HashSet<>();
        int synced = 0, notFound = 0, ambiguous = 0;
        List<String> conflicts = new java.util.ArrayList<>();
        for (Employee e : locals) {
            if (localCertificateCounts.get(e.certificate) > 1) {
                if (localConflicts.add(e.certificate)) {
                    ambiguous++;
                    conflicts.add(e.certificate);
                }
                log.warn("人员平台ID全量回填跳过：证书号 {} 在本地命中多名员工，不共用平台ID，需人工确认", e.certificate);
                continue;
            }
            List<Object> ids = byCert.get(e.certificate);
            if (ids == null || ids.isEmpty()) {
                notFound++;
                continue;
            }
            if (ids.size() > 1) {
                ambiguous++;
                conflicts.add(e.certificate);
                log.warn("人员平台ID全量回填跳过：证书号 {} 在 2.5 命中 {} 条（不自动取首条），需人工确认",
                        e.certificate, ids.size());
                continue;
            }
            e.platformId = str(ids.get(0));
            e.syncStatus = SYNCED;
            employeeMapper.updateById(e);
            synced++;
            syncedCertificates.add(e.certificate);
        }
        stats.put("platformWorkers", workers.size());
        stats.put("scanned", locals.size());
        stats.put("synced", synced);
        stats.put("notFound", notFound);
        stats.put("ambiguous", ambiguous);
        stats.put("noCertificate", 0);
        stats.put("disabled", 0);
        stats.put("conflictCertificates", conflicts);
        stats.put("backfilledAt", TimeUtil.format(TimeUtil.now()));
        log.info("人员平台ID全量回填完成: 平台返回{}人, 本地{}人, 回填{}, 未命中{}, 冲突{}",
                workers.size(), locals.size(), synced, notFound, ambiguous);
        return new BackfillOutcome(stats, syncedCertificates, new java.util.HashSet<>(conflicts), SYNCED);
    }

    /** 全量回填结果：对外统计 + 本次已回填的证书号集合（供重试判定）+ 整体状态。 */
    private static class BackfillOutcome {
        final Map<String, Object> stats;
        final Set<String> syncedCertificates;
        final Set<String> conflictCertificates;
        final String status;

        BackfillOutcome(Map<String, Object> stats, Set<String> syncedCertificates,
                        Set<String> conflictCertificates, String status) {
            this.stats = stats;
            this.syncedCertificates = syncedCertificates;
            this.conflictCertificates = conflictCertificates;
            this.status = status;
        }

        Map<String, Object> asResult(String selfStatus) {
            Map<String, Object> r = new LinkedHashMap<>(stats);
            r.put("status", selfStatus == null ? status : selfStatus);
            return r;
        }
    }

    public String backfillByCertificate(String certificate) {
        if (certificate == null || certificate.isBlank()) {
            return NO_CERT;
        }
        if (!tokenService.configured()) {
            return DISABLED;
        }
        String workEndDate = TimeUtil.date(TimeUtil.now().plusYears(1));
        List<Map<String, Object>> workers = platformClient.queryWorkList("0", workEndDate);
        List<Map<String, Object>> matched = workers.stream()
                .filter(w -> certificate.equals(str(w.get("workManCertificate"))))
                .toList();
        if (matched.isEmpty()) {
            return NOT_FOUND;
        }
        if (matched.size() > 1) {
            log.warn("人员平台ID回填跳过：证书号 {} 在 2.5 命中 {} 条（不自动取首条），需人工确认",
                    certificate, matched.size());
            return AMBIGUOUS;
        }
        Object id = matched.get(0).get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            return NOT_FOUND;
        }
        List<Employee> locals = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getCertificate, certificate));
        if (locals.size() > 1) {
            log.warn("人员平台ID回填跳过：证书号 {} 在本地命中多名员工，不共用平台ID，需人工确认", certificate);
            return AMBIGUOUS;
        }
        int n = 0;
        for (Employee e : locals) {
            e.platformId = String.valueOf(id);
            e.syncStatus = SYNCED;
            employeeMapper.updateById(e);
            n++;
        }
        if (n == 0) {
            return NOT_FOUND;
        }
        log.info("人员平台ID回填成功: certificate={}, platformId={}, rows={}", certificate, id, n);
        return SYNCED;
    }

    /** 存量回填：platform_id 为空但有证书号的员工，逐个立即查一次（不投递轮询）。 */
    public Map<String, Object> backfillAllMissing() {
        List<Employee> missing = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .and(w -> w.isNull(Employee::getPlatformId).or().eq(Employee::getPlatformId, "")));
        int synced = 0, notFound = 0, ambiguous = 0, noCert = 0, disabled = 0;
        List<String> conflicts = new java.util.ArrayList<>();
        for (Employee e : missing) {
            if (e.certificate == null || e.certificate.isBlank()) {
                noCert++;
                continue;
            }
            String r = backfillByCertificate(e.certificate);
            switch (r) {
                case SYNCED -> synced++;
                case NOT_FOUND -> notFound++;
                case AMBIGUOUS -> { ambiguous++; conflicts.add(e.certificate); }
                case DISABLED -> disabled++;
                default -> { }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", missing.size());
        out.put("synced", synced);
        out.put("notFound", notFound);
        out.put("ambiguous", ambiguous);
        out.put("noCertificate", noCert);
        out.put("disabled", disabled);
        out.put("conflictCertificates", conflicts);
        out.put("backfilledAt", TimeUtil.format(TimeUtil.now()));
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @PreDestroy
    public void shutdown() {
        retryExecutor.shutdownNow();
    }
}
