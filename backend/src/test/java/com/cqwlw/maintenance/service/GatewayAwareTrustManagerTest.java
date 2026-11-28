package com.cqwlw.maintenance.service;

import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 云网关例外只信显式配置的 SHA-256 指纹；未配置时严格 PKIX。 */
class GatewayAwareTrustManagerTest {

    private static X509Certificate cert(String subject, String issuer) throws Exception {
        return cert(subject, issuer, new byte[]{1, 2, 3});
    }

    private static X509Certificate cert(String subject, String issuer, byte[] encoded) throws Exception {
        X509Certificate c = mock(X509Certificate.class);
        when(c.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
        when(c.getIssuerX500Principal()).thenReturn(new X500Principal(issuer));
        when(c.getEncoded()).thenReturn(encoded);
        return c;
    }

    private static X509TrustManager rejecting() throws CertificateException {
        X509TrustManager d = mock(X509TrustManager.class);
        doThrow(new CertificateException("PKIX")).when(d).checkServerTrusted(any(), anyString());
        return d;
    }

    private static String fingerprint(byte[] value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }

    @Test
    void gatewaySelfSignedRejectedWithoutConfiguredFingerprint() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com");
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(rejecting(), "");
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void gatewaySelfSignedAcceptedWhenExplicitSelfSignedTrustEnabled() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com");
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), "", true);
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void otherSelfSignedRejectedEvenWhenSelfSignedTrustEnabled() throws Exception {
        X509Certificate leaf = cert("CN=evil.example.com", "CN=evil.example.com");
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), "", true);
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void nonSelfSignedWithGatewayCnRejectedEvenWhenSelfSignedTrustEnabled() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=Some CA");
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), "", true);
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void gatewaySelfSignedAcceptedOnlyWithMatchingFingerprint() throws Exception {
        byte[] encoded = {4, 5, 6};
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com", encoded);
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), fingerprint(encoded));
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void gatewaySelfSignedRejectedWithDifferentFingerprint() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com", new byte[]{7, 8, 9});
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), fingerprint(new byte[]{1}));
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void otherSelfSignedRejectedEvenWithMatchingFingerprint() throws Exception {
        byte[] encoded = {10};
        X509Certificate leaf = cert("CN=evil.example.com", "CN=evil.example.com", encoded);
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), fingerprint(encoded));
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void nonSelfSignedWithGatewayCnRejected() throws Exception {
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=Some CA");
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(
                rejecting(), fingerprint(new byte[]{1, 2, 3}));
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void defaultTrustedChainPassesThrough() throws Exception {
        X509TrustManager d = mock(X509TrustManager.class);
        WxAuthService.GatewayAwareTrustManager tm = new WxAuthService.GatewayAwareTrustManager(d, "");
        X509Certificate leaf = cert("CN=api.weixin.qq.com", "CN=DigiCert");
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{leaf}, "RSA"));
    }

    @Test
    void profilePredicate() throws Exception {
        assertTrue(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=api.weixin.qq.com", "CN=api.weixin.qq.com")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=api.weixin.qq.com", "CN=Other")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=other.qq.com", "CN=other.qq.com")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("CN=api.weixin.qq.com.attacker.com", "CN=api.weixin.qq.com.attacker.com")));
        assertFalse(WxAuthService.GatewayAwareTrustManager.isGatewayProfile(
                cert("O=api.weixin.qq.com,CN=other.example.com", "O=api.weixin.qq.com,CN=other.example.com")));
    }
}
