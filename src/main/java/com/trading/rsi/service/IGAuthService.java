package com.trading.rsi.service;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class IGAuthService {

    private final WebClient igClient;
    private final String apiKey;
    private final String username;
    private final String password;
    private final boolean enabled;

    private final AtomicReference<IGSession> session = new AtomicReference<>();

    public IGAuthService(
            @Value("${market.ig.base-url:https://demo-api.ig.com/gateway/deal}") String baseUrl,
            @Value("${market.ig.api-key:}") String apiKey,
            @Value("${market.ig.username:}") String username,
            @Value("${market.ig.password:}") String password,
            @Value("${market.ig.enabled:false}") boolean enabled,
            WebClient.Builder builder) {
        this.igClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            log.info("IG API enabled — authenticating on startup ({})", igClient.toString().contains("demo") ? "DEMO" : "LIVE");
            authenticate();
        } else {
            log.info("IG API disabled — set IG_ENABLED=true and credentials to activate indices");
        }
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty()
                && username != null && !username.isEmpty();
    }

    public WebClient getClient() {
        return igClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public IGSession getSession() {
        if (session.get() == null) {
            authenticate();
        }
        return session.get();
    }

    public void invalidateSession() {
        session.set(null);
        log.warn("IG session invalidated — will re-authenticate on next request");
    }

    public void authenticate() {
        if (!isEnabled()) {
            log.debug("IG API disabled or not configured — skipping authentication");
            return;
        }
        try {
            IGAuthRequest request = new IGAuthRequest(username, password, false);
            var response = igClient.post()
                    .uri("/session")
                    .header("X-IG-API-KEY", apiKey)
                    .header("Version", "2")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .bodyValue(request)
                    .retrieve()
                    .toEntity(Object.class)
                    .block();

            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                String cst = response.getHeaders().getFirst("CST");
                String securityToken = response.getHeaders().getFirst("X-SECURITY-TOKEN");
                if (cst != null && securityToken != null) {
                    session.set(new IGSession(cst, securityToken));
                    log.info("IG API authenticated successfully (demo={})", isDemoUrl());
                } else {
                    log.error("IG auth response missing CST or X-SECURITY-TOKEN headers");
                }
            }
        } catch (Exception e) {
            log.error("IG authentication failed: {}", e.getMessage());
        }
    }

    private boolean isDemoUrl() {
        return igClient.toString().contains("demo");
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000)
    public void refreshSession() {
        if (isEnabled() && session.get() != null) {
            log.info("Refreshing IG API session");
            authenticate();
        }
    }

    @Data
    @AllArgsConstructor
    public static class IGSession {
        private final String cst;
        private final String securityToken;
    }

    @Data
    @AllArgsConstructor
    private static class IGAuthRequest {
        private String identifier;
        private String password;
        private boolean encryptedPassword;
    }
}
