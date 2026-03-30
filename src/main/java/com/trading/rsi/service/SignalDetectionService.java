package com.trading.rsi.service;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.Candle;
import com.trading.rsi.model.RsiSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalDetectionService {
    
    private final RsiCalculator rsiCalculator;
    private final PriceHistoryService priceHistoryService;
    private final MarketDataService marketDataService;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;
    
    @Value("${rsi.period:14}")
    private int rsiPeriod;

    private static final int MINIMUM_HISTORY = 28;
    private static final long[] BACKOFF_MINUTES = {2, 5, 15, 60};

    private final Map<String, Integer> warmupFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> warmupBackoffUntil = new ConcurrentHashMap<>();
    
    public void analyzeInstrument(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        Map<String, BigDecimal> rsiValues = new HashMap<>();
        BigDecimal currentPrice = null;
        
        for (String timeframe : timeframes) {
            String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe.trim());
            log.debug("Analyzing {} with key: {}", instrument.getSymbol(), key);
            
            if (!priceHistoryService.hasMinimumHistory(key, MINIMUM_HISTORY)) {
                if (isWarmupBackedOff(key)) {
                    log.debug("Warmup backoff active for {} {} — next retry at {}", instrument.getSymbol(), timeframe.trim(), warmupBackoffUntil.get(key));
                    continue;
                }
                log.info("Insufficient history for {} {}, warming up...", instrument.getSymbol(), timeframe);
                warmupHistory(instrument, timeframe.trim(), key);
            }
            
            List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
            
            if (history.isEmpty()) {
                log.warn("No history available for {} {} (key: {})", instrument.getSymbol(), timeframe, key);
                continue;
            }
            
            log.debug("{} {} history size={} last={}",
                    instrument.getSymbol(), timeframe.trim(), history.size(), history.get(history.size() - 1));
            
            if (currentPrice == null) {
                currentPrice = history.get(history.size() - 1);
            }
            
            BigDecimal rsi = rsiCalculator.calculateRsi(history, rsiPeriod);
            
            if (rsi != null) {
                rsiValues.put(timeframe.trim(), rsi);
                log.debug("{} {} RSI: {}", instrument.getSymbol(), timeframe, rsi);
            } else {
                log.warn("RSI calculation returned null for {} {}", instrument.getSymbol(), timeframe);
            }
        }
        
        if (rsiValues.isEmpty() || currentPrice == null) {
            log.debug("Insufficient data to analyze {}", instrument.getSymbol());
            return;
        }
        
        detectSignals(instrument, rsiValues, currentPrice, timeframes.size());
    }
    
    private void detectSignals(Instrument instrument, Map<String, BigDecimal> rsiValues, 
                               BigDecimal currentPrice, int totalTimeframes) {
        
        int oversoldCount = 0;
        int overboughtCount = 0;
        BigDecimal sumRsi = BigDecimal.ZERO;
        
        for (BigDecimal rsi : rsiValues.values()) {
            sumRsi = sumRsi.add(rsi);
            
            if (rsi.compareTo(BigDecimal.valueOf(instrument.getOversoldThreshold())) < 0) {
                oversoldCount++;
            }
            if (rsi.compareTo(BigDecimal.valueOf(instrument.getOverboughtThreshold())) > 0) {
                overboughtCount++;
            }
        }
        
        BigDecimal avgRsi = sumRsi.divide(BigDecimal.valueOf(rsiValues.size()), 4, RoundingMode.HALF_UP);
        
        SignalLog.SignalType signalType = null;
        int alignedCount = 0;
        
        if (oversoldCount == totalTimeframes) {
            signalType = SignalLog.SignalType.OVERSOLD;
            alignedCount = oversoldCount;
        } else if (oversoldCount >= totalTimeframes - 1 && oversoldCount > 0) {
            signalType = SignalLog.SignalType.PARTIAL_OVERSOLD;
            alignedCount = oversoldCount;
        } else if (overboughtCount == totalTimeframes) {
            signalType = SignalLog.SignalType.OVERBOUGHT;
            alignedCount = overboughtCount;
        } else if (overboughtCount >= totalTimeframes - 1 && overboughtCount > 0) {
            signalType = SignalLog.SignalType.PARTIAL_OVERBOUGHT;
            alignedCount = overboughtCount;
        }
        
        if (signalType != null && cooldownService.shouldAlert(instrument.getSymbol(), signalType)) {
            RsiSignal signal = RsiSignal.builder()
                    .symbol(instrument.getSymbol())
                    .instrumentName(instrument.getName())
                    .signalType(signalType)
                    .currentPrice(currentPrice)
                    .rsiValues(rsiValues)
                    .timeframesAligned(alignedCount)
                    .totalTimeframes(totalTimeframes)
                    .signalStrength(calculateSignalStrength(rsiValues, signalType, instrument))
                    .build();
            
            log.info("Signal detected: {} {} - {} timeframes aligned, RSI values: {}", 
                    instrument.getName(), signalType, alignedCount, rsiValues);
            
            eventPublisher.publishEvent(new SignalEvent(this, signal));
            cooldownService.recordAlert(instrument.getSymbol(), signalType);
        }
    }
    
    private BigDecimal calculateSignalStrength(Map<String, BigDecimal> rsiValues, SignalLog.SignalType signalType, Instrument instrument) {
        BigDecimal totalDeviation = BigDecimal.ZERO;
        
        for (BigDecimal rsi : rsiValues.values()) {
            if (signalType == SignalLog.SignalType.OVERSOLD || signalType == SignalLog.SignalType.PARTIAL_OVERSOLD) {
                totalDeviation = totalDeviation.add(BigDecimal.valueOf(instrument.getOversoldThreshold()).subtract(rsi).abs());
            } else {
                totalDeviation = totalDeviation.add(rsi.subtract(BigDecimal.valueOf(instrument.getOverboughtThreshold())).abs());
            }
        }
        
        return totalDeviation.divide(BigDecimal.valueOf(rsiValues.size()), 4, RoundingMode.HALF_UP);
    }
    
    private boolean isWarmupBackedOff(String key) {
        Instant backoffUntil = warmupBackoffUntil.get(key);
        return backoffUntil != null && Instant.now().isBefore(backoffUntil);
    }

    private void recordWarmupFailure(String key, String symbol, String timeframe) {
        int failures = warmupFailures.merge(key, 1, (a, b) -> a + b);
        int backoffIndex = Math.min(failures - 1, BACKOFF_MINUTES.length - 1);
        long backoffMins = BACKOFF_MINUTES[backoffIndex];
        warmupBackoffUntil.put(key, Instant.now().plusSeconds(backoffMins * 60));
        log.warn("Warmup failed for {} {} (attempt {}) — backing off for {} min",
                symbol, timeframe, failures, backoffMins);
    }

    private void recordWarmupSuccess(String key) {
        warmupFailures.remove(key);
        warmupBackoffUntil.remove(key);
    }

    private void warmupHistory(Instrument instrument, String timeframe, String key) {
        try {
            // WARNING: Blocking call - should only be called from non-reactive threads
            // (e.g., @Scheduled methods). Calling from reactive pipelines will cause errors.
            List<Candle> candles = marketDataService.fetchCandles(instrument, timeframe, MINIMUM_HISTORY)
                    .block(Duration.ofSeconds(10));

            if (candles != null && !candles.isEmpty()) {
                BigDecimal firstPrice = candles.get(0).getClose();
                BigDecimal lastPrice = candles.get(candles.size() - 1).getClose();
                BigDecimal minPrice = firstPrice;
                BigDecimal maxPrice = firstPrice;
                for (Candle candle : candles) {
                    BigDecimal close = candle.getClose();
                    if (close.compareTo(minPrice) < 0) minPrice = close;
                    if (close.compareTo(maxPrice) > 0) maxPrice = close;
                }

                log.info("Warmup {} {}: {} candles, first={}, last={}, min={}, max={}, range={}",
                        instrument.getSymbol(), timeframe, candles.size(),
                        firstPrice, lastPrice, minPrice, maxPrice, maxPrice.subtract(minPrice));

                candles.forEach(candle -> priceHistoryService.updatePriceHistory(key, candle));
                recordWarmupSuccess(key);
            } else {
                recordWarmupFailure(key, instrument.getSymbol(), timeframe);
            }
        } catch (Exception e) {
            log.error("Error warming up history for {} {}: {}", instrument.getSymbol(), timeframe, e.getMessage());
            recordWarmupFailure(key, instrument.getSymbol(), timeframe);
        }
    }
}
