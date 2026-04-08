package com.trading.rsi.controller;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.SignalLogRepository;
import com.trading.rsi.service.MarketDataService;
import com.trading.rsi.service.NotificationService;
import com.trading.rsi.service.PriceHistoryService;
import com.trading.rsi.service.RsiCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
@Slf4j
public class SignalController {

    private final SignalLogRepository signalLogRepository;
    private final InstrumentRepository instrumentRepository;
    private final PriceHistoryService priceHistoryService;
    private final RsiCalculator rsiCalculator;
    private final MarketDataService marketDataService;
    private final NotificationService notificationService;

    @GetMapping
    public List<SignalLog> getAllSignals() {
        return signalLogRepository.findAll();
    }

    @GetMapping("/symbol/{symbol}")
    public List<SignalLog> getSignalsBySymbol(@PathVariable String symbol) {
        return signalLogRepository.findBySymbolOrderByCreatedAtDesc(symbol);
    }

    @GetMapping("/recent")
    public List<SignalLog> getRecentSignals(
            @RequestParam(defaultValue = "24") int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return signalLogRepository.findByCreatedAtAfter(since);
    }

    /**
     * Live RSI snapshot — reads current in-memory price history for all enabled instruments.
     * Useful for seeing current RSI values without waiting for a signal to fire.
     */
    @GetMapping("/rsi-snapshot")
    public Map<String, Object> getRsiSnapshot() {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        Map<String, Object> result = new LinkedHashMap<>();

        for (Instrument instrument : instruments) {
            Map<String, Object> instrumentData = new LinkedHashMap<>();
            instrumentData.put("name", instrument.getName());
            instrumentData.put("source", instrument.getSource());

            List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
            Map<String, Object> rsiData = new LinkedHashMap<>();
            BigDecimal latestPrice = null;

            for (String tf : timeframes) {
                String trimmedTf = tf.trim();
                String key = priceHistoryService.buildKey(instrument.getSymbol(), trimmedTf);
                List<BigDecimal> history = priceHistoryService.getPriceHistory(key);

                if (history.isEmpty()) {
                    rsiData.put(trimmedTf, Map.of("status", "NO_DATA"));
                    continue;
                }

                latestPrice = history.get(history.size() - 1);
                BigDecimal rsi = rsiCalculator.calculateRsi(history, 14);

                if (rsi == null) {
                    rsiData.put(trimmedTf, Map.of(
                            "status", "INSUFFICIENT_HISTORY",
                            "historySize", history.size(),
                            "required", 15
                    ));
                } else {
                    double rsiVal = rsi.doubleValue();
                    String zone;
                    if (rsiVal < 30) zone = "OVERSOLD";
                    else if (rsiVal < 40) zone = "APPROACHING_OVERSOLD";
                    else if (rsiVal > 70) zone = "OVERBOUGHT";
                    else if (rsiVal > 60) zone = "APPROACHING_OVERBOUGHT";
                    else zone = "NEUTRAL";

                    rsiData.put(trimmedTf, Map.of(
                            "rsi", rsi.setScale(2, RoundingMode.HALF_UP),
                            "zone", zone,
                            "historySize", history.size()
                    ));
                }
            }

            instrumentData.put("latestPrice", latestPrice);
            instrumentData.put("rsi", rsiData);
            result.put(instrument.getSymbol(), instrumentData);
        }

        return result;
    }

    /**
     * Retrospective RSI analysis — fetches historical candles from IG and computes RSI
     * at a given point in time to determine whether a signal would have fired.
     *
     * Example: GET /api/signals/retrospective/IX.D.DAX.DAILY.IP?at=2026-04-02T12:30:00Z
     */
    @GetMapping("/retrospective/{symbol}")
    public ResponseEntity<Map<String, Object>> getRetrospective(
            @PathVariable String symbol,
            @RequestParam String at) {

        Instant atInstant;
        try {
            atInstant = Instant.parse(at);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid 'at' parameter. Use ISO-8601 UTC format e.g. 2026-04-02T12:30:00Z"));
        }

        Optional<Instrument> instrumentOpt = instrumentRepository.findBySymbol(symbol);
        if (instrumentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Instrument instrument = instrumentOpt.get();

        if (instrument.getSource() != Instrument.DataSource.IG) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Retrospective analysis only supported for IG instruments. Source: " + instrument.getSource()));
        }

        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        Map<String, Object> rsiResults = new LinkedHashMap<>();
        int oversoldCount = 0;
        int overboughtCount = 0;

        for (String tf : timeframes) {
            String trimmedTf = tf.trim();
            int minutesPerCandle = candleMinutes(trimmedTf);
            Instant from = atInstant.minus(Duration.ofMinutes((long) 28 * minutesPerCandle));

            try {
                List<Candle> candles = marketDataService
                        .fetchCandlesInRange(instrument, trimmedTf, from, atInstant)
                        .block(Duration.ofSeconds(15));

                if (candles == null || candles.isEmpty()) {
                    rsiResults.put(trimmedTf, Map.of("status", "NO_DATA_FROM_IG"));
                    continue;
                }

                List<BigDecimal> closes = candles.stream()
                        .map(Candle::getClose)
                        .collect(Collectors.toList());

                BigDecimal rsi = rsiCalculator.calculateRsi(closes, 14);
                BigDecimal lastPrice = closes.get(closes.size() - 1);

                if (rsi == null) {
                    rsiResults.put(trimmedTf, Map.of(
                            "status", "INSUFFICIENT_CANDLES",
                            "candlesFetched", candles.size(),
                            "required", 15
                    ));
                    continue;
                }

                double rsiVal = rsi.doubleValue();
                boolean oversold = rsiVal < instrument.getOversoldThreshold();
                boolean overbought = rsiVal > instrument.getOverboughtThreshold();
                double distanceOversold = rsiVal - instrument.getOversoldThreshold();
                double distanceOverbought = instrument.getOverboughtThreshold() - rsiVal;

                if (oversold) oversoldCount++;
                if (overbought) overboughtCount++;

                Map<String, Object> tfResult = new LinkedHashMap<>();
                tfResult.put("rsi", rsi.setScale(2, RoundingMode.HALF_UP));
                tfResult.put("priceAtClose", lastPrice);
                tfResult.put("candlesFetched", candles.size());
                tfResult.put("oversoldThreshold", instrument.getOversoldThreshold());
                tfResult.put("ptsFromOversold", String.format("%.2f", distanceOversold));
                tfResult.put("ptsFromOverbought", String.format("%.2f", distanceOverbought));
                tfResult.put("oversoldAligned", oversold);
                rsiResults.put(trimmedTf, tfResult);

            } catch (Exception e) {
                log.error("Retrospective RSI error for {} {}: {}", symbol, trimmedTf, e.getMessage());
                rsiResults.put(trimmedTf, Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }

        boolean fullOversold = oversoldCount == timeframes.size();
        boolean fullOverbought = overboughtCount == timeframes.size();
        boolean signalWouldFire = fullOversold || fullOverbought;

        String verdict;
        if (fullOversold) {
            verdict = "FULL OVERSOLD signal WOULD have fired (" + oversoldCount + "/" + timeframes.size() + " timeframes aligned)";
        } else if (oversoldCount >= timeframes.size() - 1 && oversoldCount > 0) {
            verdict = "PARTIAL_OVERSOLD only — " + (timeframes.size() - oversoldCount)
                    + " timeframe(s) not aligned. No full signal fired.";
        } else if (fullOverbought) {
            verdict = "FULL OVERBOUGHT signal WOULD have fired (" + overboughtCount + "/" + timeframes.size() + " timeframes aligned)";
        } else if (overboughtCount >= timeframes.size() - 1 && overboughtCount > 0) {
            verdict = "PARTIAL_OVERBOUGHT only — no full signal fired";
        } else {
            verdict = "No signal — only " + Math.max(oversoldCount, overboughtCount) + "/" + timeframes.size() + " timeframes aligned";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("name", instrument.getName());
        response.put("analysisAt", at);
        response.put("rsi", rsiResults);
        response.put("oversoldAligned", oversoldCount);
        response.put("overboughtAligned", overboughtCount);
        response.put("totalTimeframes", timeframes.size());
        response.put("signalWouldHaveFired", signalWouldFire);
        response.put("verdict", verdict);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/mute/{symbol}")
    public ResponseEntity<Map<String, Object>> muteSymbol(@PathVariable String symbol) {
        notificationService.muteSymbol(symbol);
        return ResponseEntity.ok(Map.of("muted", symbol.toUpperCase(), "message", "All alerts suppressed for " + symbol.toUpperCase()));
    }

    @PostMapping("/unmute/{symbol}")
    public ResponseEntity<Map<String, Object>> unmuteSymbol(@PathVariable String symbol) {
        notificationService.unmuteSymbol(symbol);
        return ResponseEntity.ok(Map.of("unmuted", symbol.toUpperCase(), "message", "Alerts re-enabled for " + symbol.toUpperCase()));
    }

    @GetMapping("/muted")
    public ResponseEntity<Map<String, Object>> getMutedSymbols() {
        return ResponseEntity.ok(Map.of("muted", notificationService.getMutedSymbols()));
    }

    @PostMapping("/no-trade-mode/on")
    public ResponseEntity<Map<String, Object>> enableNoTradeMode() {
        notificationService.enableNoTradeMode();
        log.info("No-trade mode enabled via API");
        return ResponseEntity.ok(Map.of("noTradeMode", true, "message", "PARTIAL and WATCH signals suppressed. FULL signals still active."));
    }

    @PostMapping("/no-trade-mode/off")
    public ResponseEntity<Map<String, Object>> disableNoTradeMode() {
        notificationService.disableNoTradeMode();
        log.info("No-trade mode disabled via API");
        return ResponseEntity.ok(Map.of("noTradeMode", false, "message", "All signals active."));
    }

    @GetMapping("/no-trade-mode")
    public ResponseEntity<Map<String, Object>> getNoTradeModeStatus() {
        return ResponseEntity.ok(Map.of("noTradeMode", notificationService.isNoTradeModeActive()));
    }

    private int candleMinutes(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m"  -> 1;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "4h"  -> 240;
            case "1d"  -> 1440;
            default    -> 60;
        };
    }
}
