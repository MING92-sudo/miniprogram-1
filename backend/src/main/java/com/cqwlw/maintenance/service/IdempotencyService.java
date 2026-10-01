package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 写接口幂等：同 key 重放直接返回首次响应（离线队列补传/网络重试语义）。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * 「正在处理」状态的存活上限（分钟）。超过即视为上一次请求已异常终止（进程被杀、
     * commit 落库失败、业务抛错后未清理），允许同key 重新占用。
     * 不设此上限时：任何一次失败都会让该幂等键永久 422，离线队列任务将再也无法补传。
     */
    private static final long STALE_MINUTES = 10;
    /** 幂等记录保留时长（小时）：到期由定时任务清理，避免表无限增长 */
    private static final long RETAIN_HOURS = 24;

    private final IdempotencyKeyMapper mapper;

    public IdempotencyService(IdempotencyKeyMapper mapper) {
        this.mapper = mapper;
    }

    public Guard begin(String key, String path) {
        if (key == null || key.isEmpty()) {
            return new Guard(null, null);
        }
        IdempotencyKey exist = mapper.selectById(key);
        if (exist != null && exist.responseJson != null && !exist.responseJson.isEmpty()) {
            return new Guard(null, exist.responseJson);
        }
        if (exist != null) {
            if (isStale(exist)) {
                // 上一次同key 请求异常终止（未 commit 响应）：清理后允许重新占用，
                // 否则该键将永久拒绝重放，离线队列补传会被永久 422 卡住
                mapper.deleteById(key);
                log.warn("幂等键超过 {} 分钟未完成，视为上次异常终止，已回收: path={}", STALE_MINUTES, path);
            } else {
                throw new BizException(422, "相同请求正在处理，请勿重复提交");
            }
        }
        IdempotencyKey row = new IdempotencyKey();
        row.idemKey = key;
        row.path = path;
        row.createdAt = TimeUtil.now();
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同key：另一个请求刚占用，本次视为重放（多半它会成功并写入响应）
            IdempotencyKey now = mapper.selectById(key);
            if (now != null && now.responseJson != null && !now.responseJson.isEmpty()) {
                return new Guard(null, now.responseJson);
            }
            throw new BizException(422, "相同请求正在处理，请勿重复提交");
        }
        return new Guard(key, null);
    }

    private static boolean isStale(IdempotencyKey row) {
        if (row.createdAt == null) {
            return true;
        }
        return row.createdAt.plusMinutes(STALE_MINUTES).isBefore(TimeUtil.now());
    }

    /** 清理超过保留期的幂等记录（含已完成的历史响应），避免表无限增长。
     *  cron 必须是 6 段（秒 分 时 日 月 周）：Spring 的 CronExpression 拒绝 5 段表达式，
     *  而本 Bean 一旦解析失败会导致整个 Spring 上下文起不来，故不可图省事省略秒位。 */
    @Scheduled(cron = "0 17 4 * * ?", zone = "Asia/Shanghai")
    public void purgeExpired() {
        int removed = mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IdempotencyKey>()
                .lt(IdempotencyKey::getCreatedAt, TimeUtil.now().minusHours(RETAIN_HOURS)));
        if (removed > 0) {
            log.info("幂等记录清理完成，删除 {} 条（保留期 {} 小时）", removed, RETAIN_HOURS);
        }
    }

    public class Guard {
        private final String key;
        private final String replayJson;

        Guard(String key, String replayJson) {
            this.key = key;
            this.replayJson = replayJson;
        }

        public boolean replayed() {
            return replayJson != null;
        }

        public Map<String, Object> replayedResult() {
            return JsonUtil.readMap(replayJson);
        }

        public void commit(Object result) {
            if (key == null) {
                return;
            }
            IdempotencyKey row = new IdempotencyKey();
            row.idemKey = key;
            row.responseJson = JsonUtil.write(result);
            try {
                mapper.updateById(row);
            } catch (Exception e) {
                // 落库失败不阻断主流程（业务已成功），但必须留下痕迹：
                // 该键会退化为"未完成"，超过 STALE_MINUTES 后由 begin() 回收重建，
                // 否则同key 重放会被永久 422（离线队列任务将再也无法补传）
                log.error("幂等响应落库失败，该键将在 {} 分钟后被回收重建: key={}",
                        STALE_MINUTES, key, e);
            }
        }
    }
}
