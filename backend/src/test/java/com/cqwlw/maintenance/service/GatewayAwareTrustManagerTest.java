package com.cqwlw.maintenance.service;

import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** C-14 出口网关 TOFU 信任：默认严格 PKIX；仅自签+固定 CN 的网关画像在默认拒绝时放行，其余一律拒绝。 */
class GatewayAwareTrustManagerTest {

    private static X509Certificate cert(String subject, String issuer) {
        X509Certificate c = mock(X509Certificate.class);
        when(c.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
        when(c.getIssuerX500Principal()).thenReturn(new X500Principal(issuer));
        return c;
    }

    private static X509TrustManager rejecting() throws CertificateException {
        X509TrustManager d = mock(X509TrustManager.class);
        doThrow(new CertificateException("PKIX")).when(d).checkServerTrusted(any(), anyString());
        return d;
    }

    @Test
    void gatewaySelfSignedAcceptedWhenDefaultRejects() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com");
        WxAuthService.GatewayAwareTrustManager tm =
                new WxAuthService.GatewayAwareTrustManager(rejecting());
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void otherSelfSignedRejected() throws Exception {
        X509Certificate leaf = cert("CN=evil.example.com", "CN=evil.example.com");
        WxAuthService.GatewayAwareTrustManager tm =
                new WxAuthService.GatewayAwareTrustManager(rejecting());
        assertThrows(CertificateException.class,
                () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void nonSelfSignedWithGatewayCnRejected() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=Some CA");
        WxAuthService.GatewayAwareTrustManager tm =
                new WxAuthService.GatewayAwareTrustManager(rejecting());
        assertThrows(CertificateException.class,
                () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void defaultTrustedChainPassesThrough() throws Exception {
        X509TrustManager d = mock(X509TrustManager.class);
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(d);
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=DigiCert");
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void profilePredicate() {
        assertTrue(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=api.weixin.qq.com", "CN=Other")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=other.qq.com", "CN=other.qq.com")));
    }
}