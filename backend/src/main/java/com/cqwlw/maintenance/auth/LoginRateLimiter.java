package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 登录/绑定/重置密码的内存滑动窗口限流：按「场景 + IP + 账号标识」计数，窗口内超限 429，连续失败达到上限后锁定。
 * 单实例口径：多副本部署时各副本独立计数（全局限流需网关/Redis，本期不引入新组件）。
 */
@Component
public class LoginRateLimiter {

    private final long windowMillis;
    private final int maxAttempts;
    private final long lockMillis;
    private final LongSupplier clock;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(@Value("${app.rate-limit.window-seconds:60}") long windowSeconds,
                            @Value("${app.rate-limit.max-attempts:5}") int maxAttempts,
                            @Value("${app.rate-limit.lock-seconds:600}") long lockSeconds) {
        this(windowSeconds, maxAttempts, lockSeconds, System::currentTimeMillis);
    }

    /** 显式参数构造（测试可注入假时钟）；Spring 走 @Value 构造器。 */
    public LoginRateLimiter(long windowSeconds, int maxAttempts, long lockSeconds, LongSupplier clock) {
        this.windowMillis = windowSeconds * 1000;
        this.maxAttempts = maxAttempts;
        this.lockMillis = lockSeconds * 1000;
        this.clock = clock;
    }

    /** 每次请求先调用：锁定中或窗口内已达上限 → 429；否则计入本次尝试。 */
    public void checkOrThrow(String bucket, String ip, String identity) {
        State s = states.computeIfAbsent(key(bucket, ip, identity), k -> new State());
        synchronized (s) {
            long now = clock.getAsLong();
            if (now < s.lockUntilMillis) {
                throw new BizException(429, "失败次数过多，请 " + lockMinutes() + " 分钟后再试");
            }
            while (!s.attempts.isEmpty() && now - s.attempts.peekFirst() >= windowMillis) {
                s.attempts.pollFirst();
            }
            if (s.attempts.size() >= maxAttempts) {
                throw new BizException(429, "操作过于频繁，请稍后再试");
            }
            s.attempts.addLast(now);
        }
    }

    /** 校验失败（口令错/邀请码无效/未绑定等）计数，达到上限锁定该键。 */
    public void onFailure(String bucket, String ip, String identity) {
        State s = states.computeIfAbsent(key(bucket, ip, identity), k -> new State());
        synchronized (s) {
            s.consecutiveFailures++;
            if (s.consecutiveFailures >= maxAttempts) {
                s.lockUntilMillis = clock.getAsLong() + lockMillis;
                s.consecutiveFailures = 0;
            }
        }
    }

    public void onSuccess(String bucket, String ip, String identity) {
        states.remove(key(bucket, ip, identity));
    }

    private long lockMinutes() {
        return Math.max(1, lockMillis / 60_000);
    }

    private static String key(String bucket, String ip, String identity) {
        return bucket + '|' + (ip == null ? "" : ip) + '|' + (identity == null ? "" : identity);
    }

    private static final class State {
        private final Deque<Long> attempts = new ArrayDeque<>();
        private int consecutiveFailures;
        private long lockUntilMillis;
    }
}
