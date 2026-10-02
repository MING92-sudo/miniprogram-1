package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.service.FileAccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件访问签名：读文件靠它把门（小程序 image 标签不带 Authorization 头，且使用单位
 * 签字页匿名访问需要渲染签名图），因此签名必须确定性、不可跨文件复用、不可枚举。
 */
class FileAccessTokenServiceTest {

    private FileAccessTokenService token;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-0123456789abcdef");
        token = new FileAccessTokenService(props);
    }

    @Test
    void signatureCarriesExpiryAndIsFresh() {
        // 带时效：每次签发都有过期时间戳，读时重签保证客户端拿到的都是新鲜链接
        assertTrue(token.verify("file_1", token.sign("file_1")));
        // 过期令牌即使签名本身正确也必须拒绝
        String expired = token.sign("file_1", System.currentTimeMillis() / 1000 - 1);
        assertFalse(token.verify("file_1", expired));
        // 未来令牌应通过
        String future = token.sign("file_1", System.currentTimeMillis() / 1000 + 3600);
        assertTrue(token.verify("file_1", future));
    }

    @Test
    void differentFilesGetDifferentSignatures() {
        assertNotEquals(token.sign("file_1"), token.sign("file_2"));
    }

    @Test
    void verifiesItsOwnSignature() {
        assertTrue(token.verify("file_1", token.sign("file_1")));
    }

    @Test
    void rejectsSignatureOfAnotherFile() {
        // 核心防枚举：只知道 A 的签名无法用来读 B
        assertFalse(token.verify("file_2", token.sign("file_1")));
    }

    @Test
    void rejectsMissingOrMalformedSignature() {
        assertFalse(token.verify("file_1", null));
        assertFalse(token.verify("file_1", ""));
        assertFalse(token.verify("file_1", "   "));
        assertFalse(token.verify("file_1", "deadbeef"));
        assertFalse(token.verify(null, token.sign("file_1")));
    }

    @Test
    void signatureChangesWithSecret() {
        AppProperties other = new AppProperties();
        other.setJwtSecret("another-secret-0123456789abcdefg");
        assertNotEquals(token.sign("file_1"), new FileAccessTokenService(other).sign("file_1"));
    }

    @Test
    void refusesToStartWithoutSecret() {
        AppProperties blank = new AppProperties();
        blank.setJwtSecret("  ");
        // 密钥缺失不得降级为默认密钥，否则签名可被伪造
        assertThrows(IllegalStateException.class, () -> new FileAccessTokenService(blank));
    }
}