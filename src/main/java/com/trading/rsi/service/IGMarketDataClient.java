package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class IGMarketDataClient {

    private final IGAuthService authService;

    // Circuit breaker: trips when IG allowance/key is exhausted, stops ALL IG API calls
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>(null);
    private static final Duration CIRCUIT_BREAKER_DURATION = Duration.ofHours(1);

    // Track consecutive 403s per epic to distinguish session expiry from epic-specific permission errors
    private final Map<String, AtomicInteger> epic403Counts = new ConcurrentHashMap<>();
    private static final int EPIC_403_THRESHOLD = 3;
    // Epics that have been flagged as persistently forbidden — stops session invalidation cascade
    private final Set<String> blockedEpics = ConcurrentHashMap.newKeySet();

    private static final DateTimeFormatter IG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy:MM:dd-HH:mm:ss");

    private static final DateTimeFormatter IG_V3_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public boolean isCircuitOpen() {
        Instant until = circuitOpenUntil.get();
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            circuitOpenUntil.set(null);
            log.info("IG circuit breaker CLOSED — resuming API calls");
            return false;
        }
        return true;
    }

    private void tripCircuitBreaker(String reason) {
        Instant until = Instant.now().plus(CIRCUIT_BREAKER_DURATION);
        circuitOpenUntil.set(until);
        log.error("IG circuit breaker OPEN — {} — all IG calls blocked until {}", reason, until);
    }

    public Mono<List<Candle>> fetchCandles(String epic, String timeframe, int limit) {
        if (!authService.isEnabled()) {
            return Mono.error(new IllegalStateException(
                    "IG API not enabled — set market.ig.enabled=true and configure credentials"));
        }

        if (isCircuitOpen()) {
            return Mono.error(new IllegalStateException(
                    "IG circuit breaker OPEN — allowance exceeded, retry after " + circuitOpenUntil.get()));
        }

        IGAuthService.IGSession igSession = authService.getSession();
        if (igSession == null) {
            return Mono.error(new IllegalStateException("IG session unavailable — check credentials"));
        }

        String resolution = convertTimeframeToIGResolution(timeframe);

        return authService.getClient().get()
                .uri("/prices/{epic}/{resolution}/{numPoints}", epic, resolution, limit)
                .header("X-IG-API-KEY", authService.getApiKey())
                .header("CST", igSession.getCst())
                .header("X-SECURITY-TOKEN", igSession.getSecurityToken())
                .header("Version", "1")
                .retrieve()
                .bodyToMono(IGPriceResponse.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(3))
                        .filter(e -> !(e instanceof WebClientResponseException wcre
                                && wcre.getStatusCode().is4xxClientError())))
                .map(this::parseCandles)
                .doOnError(e -> handleIgError(epic, timeframe, e));
    }

    public Mono<List<Candle>> fetchCandlesInRange(String epic, String timeframe, Instant from, Instant to) {
        if (!authService.isEnabled()) {
            return Mono.error(new IllegalStateException(
                    "IG API not enabled — set market.ig.enabled=true and configure credentials"));
        }

        if (isCircuitOpen()) {
            return Mono.error(new IllegalStateException(
                    "IG circuit breaker OPEN — allowance exceeded, retry after " + circuitOpenUntil.get()));
        }

        IGAuthService.IGSession igSession = authService.getSession();
        if (igSession == null) {
            return Mono.error(new IllegalStateException("IG session unavailable — check credentials"));
        }

        String resolution = convertTimeframeToIGResolution(timeframe);
        String fromStr = LocalDateTime.ofInstant(from, ZoneOffset.UTC).format(IG_V3_DATE_FORMAT);
        String toStr = LocalDateTime.ofInstant(to, ZoneOffset.UTC).format(IG_V3_DATE_FORMAT);

        return authService.getClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path("/prices/{epic}")
                        .queryParam("resolution", resolution)
                        .queryParam("from", fromStr)
                        .queryParam("to", toStr)
                        .queryParam("pageSize", "1000")
                        .build(epic))
                .header("X-IG-API-KEY", authService.getApiKey())
                .header("CST", igSession.getCst())
                .header("X-SECURITY-TOKEN", igSession.getSecurityToken())
                .header("Version", "3")
                .retrieve()
                .bodyToMono(IGPriceResponse.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(3))
                        .filter(e -> !(e instanceof WebClientResponseException wcre
                                && wcre.getStatusCode().is4xxClientError())))
                .map(this::parseCandles)
                .doOnError(e -> handleIgError(epic, timeframe, e));
    }

    private void handleIgError(String epic, String timeframe, Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            String body = wcre.getResponseBodyAsString();
            if (status == 403) {
                // Check for allowance exceeded — trip circuit breaker, don't invalidate session
                if (body.contains("exceeded-account-historical-data-allowance") ||
                        body.contains("exceeded-api-key-allowance")) {
                    tripCircuitBreaker("IG allowance exceeded: " + body);
                    return;
                }
                int count = epic403Counts
                        .computeIfAbsent(epic, k -> new AtomicInteger(0))
                        .incrementAndGet();
                if (count == 1) {
                    // First 403 for this epic — could be session expiry, invalidate once
                    log.warn("IG 403 for {} {} — invalidating session (first occurrence, body={})",
                            epic, timeframe, body);
                    authService.invalidateSession();
                } else if (count >= EPIC_403_THRESHOLD && blockedEpics.add(epic)) {
                    // Persistent 403 — this epic is forbidden, not a session issue
                    log.error("IG 403 persists for {} after {} attempts — epic likely invalid or unauthorized. " +
                            "Blocking further session invalidations for this epic. Check/disable in DB. body={}",
                            epic, count, body);
                } else {
                    log.debug("IG 403 for {} {} (attempt {}, blocked={}) — skipping session invalidation",
                            epic, timeframe, count, blockedEpics.contains(epic));
                }
            } else if (status == 400) {
                log.debug("IG 400 for {} {} — market closed or data unavailable", epic, timeframe);
            } else {
                log.warn("IG HTTP {} for {} {}: {}", status, epic, timeframe, wcre.getMessage());
            }
        } else {
            log.warn("IG fetch error for {} {}: {}", epic, timeframe, e.getMessage());
        }
    }

    // Reset 403 tracking — call after successful session refresh
    public void resetEpic403Tracking() {
        epic403Counts.clear();
        blockedEpics.clear();
    }

    private List<Candle> parseCandles(IGPriceResponse response) {
        if (response == null || response.getPrices() == null) {
            return List.of();
        }
        return response.getPrices().stream()
                .map(p -> Candle.builder()
                        .timestamp(parseTime(p.getSnapshotTime()))
                        .open(mid(p.getOpenPrice()))
                        .high(mid(p.getHighPrice()))
                        .low(mid(p.getLowPrice()))
                        .close(mid(p.getClosePrice()))
                        .volume(BigDecimal.valueOf(p.getLastTradedVolume() != null ? p.getLastTradedVolume() : 0L))
                        .build())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private BigDecimal mid(Object priceObj) {
        if (priceObj == null) return BigDecimal.ZERO;
        Map<String, Object> map = (Map<String, Object>) priceObj;
        Object bid = map.get("bid");
        Object ask = map.get("ask");
        if (bid == null && ask == null) return BigDecimal.ZERO;
        if (bid == null) return new BigDecimal(ask.toString());
        if (ask == null) return new BigDecimal(bid.toString());
        return new BigDecimal(bid.toString())
                .add(new BigDecimal(ask.toString()))
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    }

    private Instant parseTime(String snapshotTime) {
        if (snapshotTime == null) return Instant.now();
        try {
            return LocalDateTime.parse(snapshotTime, IG_TIME_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(snapshotTime, IG_V3_DATE_FORMAT).toInstant(ZoneOffset.UTC);
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private String convertTimeframeToIGResolution(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m"  -> "MINUTE";
            case "2m"  -> "MINUTE_2";
            case "3m"  -> "MINUTE_3";
            case "5m"  -> "MINUTE_5";
            case "10m" -> "MINUTE_10";
            case "15m" -> "MINUTE_15";
            case "30m" -> "MINUTE_30";
            case "1h"  -> "HOUR";
            case "2h"  -> "HOUR_2";
            case "3h"  -> "HOUR_3";
            case "4h"  -> "HOUR_4";
            case "1d"  -> "DAY";
            case "1w"  -> "WEEK";
            default    -> "MINUTE";
        };
    }

    @Data
    private static class IGPriceResponse {
        private List<IGPrice> prices;
        private String instrumentType;
    }

    @Data
    private static class IGPrice {
        private String snapshotTime;
        private String snapshotTimeUTC;
        private Object openPrice;
        private Object highPrice;
        private Object lowPrice;
        private Object closePrice;
        private Long lastTradedVolume;
    }
}
