package com.cqwlw.maintenance.config;

import com.cqwlw.maintenance.service.WxAuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate platformRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(f);
    }

    @Bean
    public RestTemplate wxRestTemplate(AppProperties props) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{
                    WxAuthService.GatewayAwareTrustManager.withDefaultTrust(
                            props.getWxGatewayTrustedSha256(), props.isWxGatewayTrustSelfSigned())},
                    new SecureRandom());
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                    super.prepareConnection(connection, httpMethod);
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(ctx.getSocketFactory());
                    }
                }
            };
            f.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            f.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            return new RestTemplate(f);
        } catch (Exception e) {
            throw new IllegalStateException("wxRestTemplate init failed", e);
        }
    }
}
