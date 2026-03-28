package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class IGMarketDataClient {

    private final IGAuthService authService;

    private static final DateTimeFormatter IG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy:MM:dd-HH:mm:ss");

    public Mono<List<Candle>> fetchCandles(String epic, String timeframe, int limit) {
        if (!authService.isEnabled()) {
            return Mono.error(new IllegalStateException(
                    "IG API not enabled — set market.ig.enabled=true and configure credentials"));
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
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(3)))
                .map(this::parseCandles)
                .doOnError(e -> {
                    log.error("IG candle fetch failed for {} {}: {}", epic, timeframe, e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("403")) {
                        authService.invalidateSession();
                    }
                });
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
            return Instant.now();
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
        private Object openPrice;
        private Object highPrice;
        private Object lowPrice;
        private Object closePrice;
        private Long lastTradedVolume;
    }
}
