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
    
    @Value("${rsi.oversold-threshold:30}")
    private int oversoldThreshold;
    
    @Value("${rsi.overbought-threshold:70}")
    private int overboughtThreshold;
    
    private static final int MINIMUM_HISTORY = 28;
    
    public void analyzeInstrument(Instrument instrument) {
        List<String> timeframes = Arrays.asList(instrument.getTimeframes().split(","));
        Map<String, BigDecimal> rsiValues = new HashMap<>();
        BigDecimal currentPrice = null;
        
        for (String timeframe : timeframes) {
            String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe.trim());
            
            if (!priceHistoryService.hasMinimumHistory(key, MINIMUM_HISTORY)) {
                warmupHistory(instrument, timeframe.trim());
            }
            
            LinkedList<BigDecimal> history = priceHistoryService.getPriceHistory(key);
            
            if (history.isEmpty()) {
                log.debug("No history available for {} {}", instrument.getSymbol(), timeframe);
                continue;
            }
            
            if (currentPrice == null) {
                currentPrice = history.getLast();
            }
            
            BigDecimal rsi = rsiCalculator.calculateRsi(history, rsiPeriod);
            
            if (rsi != null) {
                rsiValues.put(timeframe.trim(), rsi);
                log.debug("{} {} RSI: {}", instrument.getSymbol(), timeframe, rsi);
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
        
        BigDecimal avgRsi = sumRsi.divide(BigDecimal.valueOf(rsiValues.size()), 4, BigDecimal.ROUND_HALF_UP);
        
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
                    .signalStrength(calculateSignalStrength(rsiValues, signalType))
                    .build();
            
            log.info("Signal detected: {} {} - {} timeframes aligned", 
                    instrument.getName(), signalType, alignedCount);
            
            eventPublisher.publishEvent(new SignalEvent(this, signal));
            cooldownService.recordAlert(instrument.getSymbol(), signalType);
        }
    }
    
    private BigDecimal calculateSignalStrength(Map<String, BigDecimal> rsiValues, SignalLog.SignalType signalType) {
        BigDecimal totalDeviation = BigDecimal.ZERO;
        
        for (BigDecimal rsi : rsiValues.values()) {
            if (signalType == SignalLog.SignalType.OVERSOLD || signalType == SignalLog.SignalType.PARTIAL_OVERSOLD) {
                totalDeviation = totalDeviation.add(BigDecimal.valueOf(30).subtract(rsi).abs());
            } else {
                totalDeviation = totalDeviation.add(rsi.subtract(BigDecimal.valueOf(70)).abs());
            }
        }
        
        return totalDeviation.divide(BigDecimal.valueOf(rsiValues.size()), 4, BigDecimal.ROUND_HALF_UP);
    }
    
    private void warmupHistory(Instrument instrument, String timeframe) {
        try {
            marketDataService.fetchCandles(instrument, timeframe, MINIMUM_HISTORY)
                    .subscribe(candles -> {
                        String key = priceHistoryService.buildKey(instrument.getSymbol(), timeframe);
                        candles.forEach(candle -> priceHistoryService.updatePriceHistory(key, candle));
                        log.info("Warmed up {} candles for {} {}", candles.size(), instrument.getSymbol(), timeframe);
                    });
        } catch (Exception e) {
            log.error("Error warming up history for {} {}: {}", instrument.getSymbol(), timeframe, e.getMessage());
        }
    }
}
