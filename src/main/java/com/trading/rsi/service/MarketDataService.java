package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketDataService {
    
    private final WebClient binanceClient;
    private final WebClient finnhubClient;
    private final String finnhubApiKey;
    private final IGMarketDataClient igMarketDataClient;
    
    public MarketDataService(
            @Value("${market.binance.base-url}") String binanceUrl,
            @Value("${market.finnhub.base-url}") String finnhubUrl,
            @Value("${market.finnhub.api-key:}") String finnhubApiKey,
            IGMarketDataClient igMarketDataClient,
            WebClient.Builder webClientBuilder) {
        this.binanceClient = webClientBuilder.baseUrl(binanceUrl).build();
        this.finnhubClient = webClientBuilder.baseUrl(finnhubUrl).build();
        this.finnhubApiKey = finnhubApiKey;
        this.igMarketDataClient = igMarketDataClient;
    }
    
    public Mono<List<Candle>> fetchCandles(Instrument instrument, String timeframe, int limit) {
        return switch (instrument.getSource()) {
            case BINANCE -> fetchBinanceCandles(instrument.getSymbol(), timeframe, limit);
            case FINNHUB -> fetchFinnhubCandles(instrument.getSymbol(), timeframe, limit);
            case IG -> igMarketDataClient.fetchCandles(instrument.getSymbol(), timeframe, limit);
            case TWELVE_DATA -> Mono.error(new UnsupportedOperationException(
                    "Twelve Data free tier rate limits (8/min) are too low for real-time polling — use IG API instead"));
        };
    }
    
    private Mono<List<Candle>> fetchBinanceCandles(String symbol, String timeframe, int limit) {
        String interval = convertTimeframeToBinanceInterval(timeframe);
        
        return binanceClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<List<?>>>() {})
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(e -> !(e instanceof WebClientResponseException wcre
                                && (wcre.getStatusCode().value() == 418 || wcre.getStatusCode().value() == 429))))
                .map(this::parseBinanceCandles)
                .doOnError(e -> {
                    if (e instanceof WebClientResponseException wcre) {
                        log.error("Binance HTTP {} for {} {}: {}",
                                wcre.getStatusCode().value(), symbol, timeframe, binanceRateLimitHint(wcre.getStatusCode().value()));
                    } else {
                        log.error("Error fetching Binance candles for {} {}: {}", symbol, timeframe, e.getMessage());
                    }
                });
    }
    
    private Mono<List<Candle>> fetchFinnhubCandles(String symbol, String timeframe, int limit) {
        if (finnhubApiKey == null || finnhubApiKey.isEmpty()) {
            return Mono.error(new IllegalStateException("Finnhub API key not configured"));
        }
        
        String resolution = convertTimeframeToFinnhubResolution(timeframe);
        long endTime = Instant.now().getEpochSecond();
        long startTime = calculateStartTime(endTime, timeframe, limit);
        
        return finnhubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/stock/candle")
                        .queryParam("symbol", symbol)
                        .queryParam("resolution", resolution)
                        .queryParam("from", startTime)
                        .queryParam("to", endTime)
                        .queryParam("token", finnhubApiKey)
                        .build())
                .retrieve()
                .bodyToMono(FinnhubCandleResponse.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .map(this::parseFinnhubCandles)
                .doOnError(e -> log.error("Error fetching Finnhub candles for {}: {}", symbol, e.getMessage()));
    }
    
    private List<Candle> parseBinanceCandles(List<List<?>> rawData) {
        return rawData.stream()
                .map(kline -> Candle.builder()
                        .timestamp(Instant.ofEpochMilli(((Number) kline.get(0)).longValue()))
                        .open(new BigDecimal(kline.get(1).toString()))
                        .high(new BigDecimal(kline.get(2).toString()))
                        .low(new BigDecimal(kline.get(3).toString()))
                        .close(new BigDecimal(kline.get(4).toString()))
                        .volume(new BigDecimal(kline.get(5).toString()))
                        .build())
                .collect(Collectors.toList());
    }
    
    private List<Candle> parseFinnhubCandles(FinnhubCandleResponse response) {
        if ("no_data".equals(response.getS())) {
            return List.of();
        }
        
        List<Candle> candles = new java.util.ArrayList<>();
        for (int i = 0; i < response.getT().size(); i++) {
            candles.add(Candle.builder()
                    .timestamp(Instant.ofEpochSecond(response.getT().get(i)))
                    .open(response.getO().get(i))
                    .high(response.getH().get(i))
                    .low(response.getL().get(i))
                    .close(response.getC().get(i))
                    .volume(response.getV().get(i))
                    .build());
        }
        return candles;
    }
    
    private String convertTimeframeToBinanceInterval(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> "1m";
            case "3m" -> "3m";
            case "5m" -> "5m";
            case "15m" -> "15m";
            case "30m" -> "30m";
            case "1h" -> "1h";
            case "4h" -> "4h";
            case "1d" -> "1d";
            case "2d" -> "2d";
            default -> "1m";
        };
    }
    
    private String convertTimeframeToFinnhubResolution(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> "1";
            case "5m" -> "5";
            case "15m" -> "15";
            case "30m" -> "30";
            case "1h" -> "60";
            case "4h" -> "240";
            case "1d" -> "D";
            default -> "1";
        };
    }
    
    private long calculateStartTime(long endTime, String timeframe, int limit) {
        int minutesPerCandle = switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "4h" -> 240;
            case "1d" -> 1440;
            default -> 1;
        };
        return endTime - (minutesPerCandle * 60L * limit);
    }
    
    private static String binanceRateLimitHint(int status) {
        return switch (status) {
            case 418 -> "IP banned by Binance — retrying makes the ban longer. Wait before restarting.";
            case 429 -> "Rate limit exceeded — reduce polling frequency or add delays between requests.";
            default -> "Unexpected HTTP error";
        };
    }

    @lombok.Data
    private static class FinnhubCandleResponse {
        private List<BigDecimal> c;
        private List<BigDecimal> h;
        private List<BigDecimal> l;
        private List<BigDecimal> o;
        private String s;
        private List<Long> t;
        private List<BigDecimal> v;
    }
}
