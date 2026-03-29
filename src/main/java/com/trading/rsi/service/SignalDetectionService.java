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
import java.util.*;

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
    
    public void analyzeInstrument(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        Map<String, BigDecimal> rsiValues = new HashMap<>();
        BigDecimal currentPrice = null;
        
        for (String timeframe : timeframes) {
            String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe.trim());
            log.debug("Analyzing {} with key: {}", instrument.getSymbol(), key);
            
            if (!priceHistoryService.hasMinimumHistory(key, MINIMUM_HISTORY)) {
                log.info("Insufficient history for {} {}, warming up...", instrument.getSymbol(), timeframe);
                warmupHistory(instrument, timeframe.trim());
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
    
    private void warmupHistory(Instrument instrument, String timeframe) {
        try {
            // WARNING: Blocking call - should only be called from non-reactive threads
            // (e.g., @Scheduled methods). Calling from reactive pipelines will cause errors.
            List<Candle> candles = marketDataService.fetchCandles(instrument, timeframe, MINIMUM_HISTORY)
                    .block(Duration.ofSeconds(10));
            
            if (candles != null && !candles.isEmpty()) {
                String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe);
                
                // Calculate min/max in single pass
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
            }
        } catch (Exception e) {
            log.error("Error warming up history for {} {}: {}", instrument.getSymbol(), timeframe, e.getMessage());
        }
    }
}
