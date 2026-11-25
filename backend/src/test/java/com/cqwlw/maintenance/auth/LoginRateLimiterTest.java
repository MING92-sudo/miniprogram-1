package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 内存滑动窗口限流：窗口内第 6 次拒绝、连续失败锁定 10 分钟、窗口过后恢复。 */
class LoginRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private LoginRateLimiter limiter() {
        return new LoginRateLimiter(60, 5, 600, now::get);
    }

    @Test
    void sixthAttemptWithinWindowRejected() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            limiter.checkOrThrow("login", "1.1.1.1", "13800000001");
        }

        BizException ex = assertThrows429(() -> limiter.checkOrThrow("login", "1.1.1.1", "13800000001"));
        assertEquals(429, ex.getCode());

        // 其他账号/其他 IP 不受影响
        assertDoesNotThrow(() -> limiter.checkOrThrow("login", "1.1.1.1", "13800000002"));
        assertDoesNotThrow(() -> limiter.checkOrThrow("login", "2.2.2.2", "13800000001"));
    }

    @Test
    void consecutiveFailuresLockForLockPeriod() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            limiter.checkOrThrow("login", "1.1.1.1", "13800000001");
            limiter.onFailure("login", "1.1.1.1", "13800000001");
        }

        // 锁定期间：即使滑出计数窗口仍拒绝
        now.addAndGet(61_000);
        BizException ex = assertThrows429(() -> limiter.checkOrThrow("login", "1.1.1.1", "13800000001"));
        assertEquals(429, ex.getCode());

        // 锁定 10 分钟过后恢复
        now.addAndGet(600_000);
        assertDoesNotThrow(() -> limiter.checkOrThrow("login", "1.1.1.1", "13800000001"));
    }

    @Test
    void windowExpiryRestoresAccess() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            limiter.checkOrThrow("bind-wechat", "1.1.1.1", "ABC23DEF");
        }
        assertThrows429(() -> limiter.checkOrThrow("bind-wechat", "1.1.1.1", "ABC23DEF"));

        now.addAndGet(61_000);
        assertDoesNotThrow(() -> limiter.checkOrThrow("bind-wechat", "1.1.1.1", "ABC23DEF"));
    }

    @Test
    void successClearsFailureCount() {
        LoginRateLimiter limiter = limiter();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                limiter.checkOrThrow("login", "1.1.1.1", "13800000001");
                limiter.onFailure("login", "1.1.1.1", "13800000001");
            }
            limiter.onSuccess("login", "1.1.1.1", "13800000001");
            now.addAndGet(61_000);
        }
        // 若未清零，第 3 轮已累计 ≥5 次失败触发锁定；清零后仍可尝试
        assertDoesNotThrow(() -> limiter.checkOrThrow("login", "1.1.1.1", "13800000001"));
    }

    private static BizException assertThrows429(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(BizException.class, action);
    }
}
