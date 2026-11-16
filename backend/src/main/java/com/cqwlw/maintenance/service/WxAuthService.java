package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * 微信小程序登录凭证校验（code → openid，微信 auth.code2Session）：
 * GET https://api.weixin.qq.com/sns/jscode2session?appid=&secret=&js_code=&grant_type=authorization_code
 *
 * <p>openid 只能由微信签发的 code 换取，禁止用 dev_openid 或客户端可控值兜底——否则全员落库
 * 同一 openid，按 openid 查人会串号。凭证只存在于后端；日志不打印 code/secret/完整 openid。
 */
@Service
public class WxAuthService {

    private static final Logger log = LoggerFactory.getLogger(WxAuthService.class);

    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final AppProperties props;
    private final RestTemplate restTemplate;

    public WxAuthService(AppProperties props, @Qualifier("wxRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    /** WX_APPID / WX_APPSECRET 是否已注入 */
    public boolean configured() {
        return props.wxConfigured();
    }

    /**
     * 用 wx.login 的 code 换取真实 openid。
     *
     * @throws BizException 422 缺 code 或 code 无效/已使用/超频（客户端应重新 wx.login；<b>不能用 401</b>——
     *                      前端 request.js 对 401 会清登录态，bind-wechat 刚登录完就可能被误登出）；
     *                      2001 未配置 WX_APPID/WX_APPSECRET 或微信接口不可用（服务端问题）
     */
    public String openidFromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BizException(422, "缺少微信 code，请重新进入小程序登录");
        }
        if (!configured()) {
            throw new BizException(2001, "微信登录未配置（WX_APPID / WX_APPSECRET）");
        }
        // 凭证仅拼入请求 URL；日志不得输出完整 URL
        String url = UriComponentsBuilder.fromHttpUrl(JSCODE2SESSION_URL)
                .queryParam("appid", props.getWxAppid())
                .queryParam("secret", props.getWxAppsecret())
                .queryParam("js_code", code.trim())
                .queryParam("grant_type", "authorization_code")
                .build().encode().toUriString();
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            Map<String, Object> body = MAPPER.readValue(String.valueOf(resp.getBody()),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (body == null) {
                log.warn("jscode2session 返回空报文");
                throw new BizException(2001, "微信登录失败，请稍后重试");
            }
            Object openid = body.get("openid");
            if (openid != null && !String.valueOf(openid).trim().isEmpty()) {
                log.debug("jscode2session 成功"); // 不打印 openid（脱敏）
                return String.valueOf(openid).trim();
            }
            Integer errcode = errcode(body.get("errcode"));
            log.warn("jscode2session 失败: errcode={}, errmsg={}", errcode, body.get("errmsg"));
            throw new BizException(errorCode(errcode), errorMessage(errcode));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 只记异常类型与脱敏后的 cause：请求 URL 含 appid/secret/js_code，
            // HttpURLConnection 非 2xx 的 IOException message 会回显完整 URL，落日志前必须截掉查询串（AGENTS §2.6）
            Throwable cause = e.getCause();
            log.warn("微信 jscode2session 请求异常: {} cause={} {}", e.getClass().getSimpleName(),
                    cause == null ? "-" : cause.getClass().getSimpleName(),
                    sanitizeCauseMessage(cause == null ? null : cause.getMessage()));
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                logPeerChain();
            }
            throw new BizException(2001, "微信登录失败，请稍后重试");
        }
    }

    /**
     * 异常 message 落日志前脱敏：截掉 URL 查询串（appid/secret/js_code 均在查询参数），
     * 保留 HTTP 码/主机路径等排障上下文；空消息归一为 "-"。
     * @param msg 原始异常 message（可能为 null）
     * @return 不含凭证查询参数的日志安全文本
     */
    static String sanitizeCauseMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return "-";
        }
        int q = msg.indexOf('?');
        return q < 0 ? msg : msg.substring(0, q) + "?<query-redacted>";
    }

    /** SSL 失败时捕获对端证书链并落 issuer（仅诊断：trust-all 只用于读证书，不发送任何凭证，不影响真实请求校验）。 */
    private void logPeerChain() {
        try {
            javax.net.ssl.TrustManager[] capture = new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }};
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, capture, new java.security.SecureRandom());
            try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                    .createSocket("api.weixin.qq.com", 443)) {
                socket.startHandshake();
                log.warn("jscode2session 解析 IP: {}", java.util.Arrays.toString(
                        java.net.InetAddress.getAllByName("api.weixin.qq.com")));
                for (java.security.cert.Certificate cert : socket.getSession().getPeerCertificates()) {
                    if (cert instanceof java.security.cert.X509Certificate x509) {
                        log.warn("jscode2session 对端证书: subject={} issuer={} sha256={}",
                                x509.getSubjectX500Principal(), x509.getIssuerX500Principal(),
                                HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("jscode2session 对端证书链捕获失败: {}", ex.getClass().getSimpleName());
        }
    }

    private static Integer errcode(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** -1 系统繁忙 / 40013+40125 凭证问题归为服务端配置错误（2001）；code 类问题归为业务校验失败（422，见方法注释） */
    private static int errorCode(Integer errcode) {
        if (errcode != null && (errcode == -1 || errcode == 40013 || errcode == 40125)) {
            return 2001;
        }
        return 422;
    }

    private static String errorMessage(Integer errcode) {
        if (errcode == null) {
            return "微信登录失败，请稍后重试";
        }
        switch (errcode) {
            case -1:
                return "微信服务繁忙，请稍后重试";
            case 40029:
            case 40163:
                return "微信登录凭证已失效，请重新进入小程序登录";
            case 45011:
                return "微信登录过于频繁，请稍后重试";
            case 40013:
                return "微信登录未配置或 AppID 无效（WX_APPID）";
            case 40125:
                return "微信登录未配置或 AppSecret 无效（WX_APPSECRET）";
            default:
                return "微信登录失败（errcode=" + errcode + "），请重新进入小程序登录";
        }
    }

    /**
     * 云环境出口网关 TLS 拦截适配（C-14，用户裁决 2026-11-15：信任出口网关证书）。
     * 网关对 443 呈现轮换的自签证书（CN=api.weixin.qq.com），静态导入不可行；
     * 默认仍走严格 PKIX，仅当校验失败且对端符合网关画像（自签+固定 CN）时 TOFU 信任并留指纹审计。
     * 信任边界=本云环境出口基础设施（密钥亦存于该环境）；非网关画像的失败一律拒绝。
     */
    public static final class GatewayAwareTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;

        public GatewayAwareTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        public static GatewayAwareTrustManager withDefaultTrust() {
            try {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);
                for (TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager x) {
                        return new GatewayAwareTrustManager(x);
                    }
                }
                throw new IllegalStateException("no X509TrustManager");
            } catch (Exception e) {
                throw new IllegalStateException("default trust init failed", e);
            }
        }

        static boolean isGatewayProfile(X509Certificate leaf) {
            return leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())
                    && leaf.getSubjectX500Principal().getName().contains("CN=api.weixin.qq.com");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                if (chain.length > 0 && isGatewayProfile(chain[0])) {
                    try {
                        log.warn("信任云出口网关自签证书（C-14 用户裁决）: sha256={}", HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())));
                    } catch (Exception ignored) {
                        // 指纹仅审计用，失败不影响信任决策
                    }
                    return;
                }
                throw e;
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
