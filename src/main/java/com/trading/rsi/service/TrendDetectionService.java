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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects strong trends using consecutive overbought/oversold signal counts
 * and generates TREND_BUY_DIP / TREND_SELL_RALLY signals when price pulls back
 * within a confirmed trend.
 *
 * Logic:
 *  - Track consecutive OVERBOUGHT signals per symbol (reset on OVERSOLD or timeout)
 *  - After N consecutive overbought signals → mark STRONG_UPTREND
 *  - In a strong uptrend: suppress further OVERBOUGHT sell signals
 *  - When RSI dips toward a configurable "dip zone" → generate TREND_BUY_DIP
 *  - Mirror logic for downtrends (TREND_SELL_RALLY)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendDetectionService {

    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;

    @Value("${rsi.trend.consecutive-signals-for-trend:3}")
    private int consecutiveSignalsForTrend;

    @Value("${rsi.trend.dip-rsi-upper:55}")
    private double dipRsiUpper;

    @Value("${rsi.trend.dip-rsi-lower:40}")
    private double dipRsiLower;

    @Value("${rsi.trend.rally-rsi-upper:60}")
    private double rallyRsiUpper;

    @Value("${rsi.trend.rally-rsi-lower:45}")
    private double rallyRsiLower;

    @Value("${rsi.trend.trend-timeout-hours:12}")
    private int trendTimeoutHours;

    @Value("${rsi.trend.suppress-counter-trend:true}")
    private boolean suppressCounterTrend;

    // Tracks consecutive overbought signal count per symbol
    private final Map<String, Integer> consecutiveOverbought = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveOversold = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOverboughtTime = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOversoldTime = new ConcurrentHashMap<>();

    public enum TrendState {
        STRONG_UPTREND,
        STRONG_DOWNTREND,
        NEUTRAL
    }

    /**
     * Record a full OVERBOUGHT or OVERSOLD signal, updating consecutive counters.
     */
    public void recordSignal(String symbol, SignalLog.SignalType signalType) {
        Instant now = Instant.now();

        if (signalType == SignalLog.SignalType.OVERBOUGHT) {
            // Check for timeout — reset if it's been too long since last overbought
            Instant last = lastOverboughtTime.get(symbol);
            if (last != null && last.isBefore(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
                consecutiveOverbought.put(symbol, 0);
            }
            int count = consecutiveOverbought.merge(symbol, 1, Integer::sum);
            lastOverboughtTime.put(symbol, now);
            // Reset opposite counter
            consecutiveOversold.put(symbol, 0);

            if (count >= consecutiveSignalsForTrend) {
                log.info("STRONG UPTREND detected for {} — {} consecutive overbought signals", symbol, count);
            }
        } else if (signalType == SignalLog.SignalType.OVERSOLD) {
            Instant last = lastOversoldTime.get(symbol);
            if (last != null && last.isBefore(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
                consecutiveOversold.put(symbol, 0);
            }
            int count = consecutiveOversold.merge(symbol, 1, Integer::sum);
            lastOversoldTime.put(symbol, now);
            consecutiveOverbought.put(symbol, 0);

            if (count >= consecutiveSignalsForTrend) {
                log.info("STRONG DOWNTREND detected for {} — {} consecutive oversold signals", symbol, count);
            }
        }
    }

    /**
     * Get current trend state for a symbol.
     */
    public TrendState getTrendState(String symbol) {
        Instant now = Instant.now();

        int obCount = consecutiveOverbought.getOrDefault(symbol, 0);
        Instant lastOb = lastOverboughtTime.get(symbol);
        if (obCount >= consecutiveSignalsForTrend && lastOb != null
                && lastOb.isAfter(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
            return TrendState.STRONG_UPTREND;
        }

        int osCount = consecutiveOversold.getOrDefault(symbol, 0);
        Instant lastOs = lastOversoldTime.get(symbol);
        if (osCount >= consecutiveSignalsForTrend && lastOs != null
                && lastOs.isAfter(now.minus(trendTimeoutHours, ChronoUnit.HOURS))) {
            return TrendState.STRONG_DOWNTREND;
        }

        return TrendState.NEUTRAL;
    }

    /**
     * Returns the consecutive overbought count for a symbol.
     */
    public int getConsecutiveOverboughtCount(String symbol) {
        return consecutiveOverbought.getOrDefault(symbol, 0);
    }

    /**
     * Returns the consecutive oversold count for a symbol.
     */
    public int getConsecutiveOversoldCount(String symbol) {
        return consecutiveOversold.getOrDefault(symbol, 0);
    }

    /**
     * Whether to suppress an OVERBOUGHT sell signal because we're in a strong uptrend.
     */
    public boolean shouldSuppressCounterTrend(String symbol, SignalLog.SignalType signalType) {
        if (!suppressCounterTrend) return false;

        TrendState trend = getTrendState(symbol);
        if (trend == TrendState.STRONG_UPTREND && signalType == SignalLog.SignalType.OVERBOUGHT) {
            log.info("Suppressing OVERBOUGHT sell signal for {} — strong uptrend active ({} consecutive)",
                    symbol, consecutiveOverbought.getOrDefault(symbol, 0));
            return true;
        }
        if (trend == TrendState.STRONG_DOWNTREND && signalType == SignalLog.SignalType.OVERSOLD) {
            log.info("Suppressing OVERSOLD buy signal for {} — strong downtrend active ({} consecutive)",
                    symbol, consecutiveOversold.getOrDefault(symbol, 0));
            return true;
        }
        return false;
    }

    /**
     * Check if RSI values indicate a dip in an uptrend (buy opportunity)
     * or a rally in a downtrend (sell opportunity).
     * Called on every poll cycle when no full signal fires.
     */
    public void checkForTrendEntry(Instrument instrument, Map<String, BigDecimal> rsiValues,
                                    BigDecimal currentPrice, Candle triggerCandle) {
        TrendState trend = getTrendState(instrument.getSymbol());
        if (trend == TrendState.NEUTRAL) return;

        // Get the fastest timeframe RSI as trigger
        BigDecimal fastestRsi = getFastestRsi(rsiValues, instrument.getTimeframes());
        if (fastestRsi == null) return;
        double fastRsi = fastestRsi.doubleValue();

        if (trend == TrendState.STRONG_UPTREND) {
            // Buy the dip: fastest RSI has pulled back into the dip zone
            if (fastRsi >= dipRsiLower && fastRsi <= dipRsiUpper) {
                SignalLog.SignalType type = SignalLog.SignalType.TREND_BUY_DIP;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int obCount = consecutiveOverbought.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(50 - fastRsi).abs()
                            .setScale(4, RoundingMode.HALF_UP);

                    RsiSignal signal = RsiSignal.builder()
                            .symbol(instrument.getSymbol())
                            .instrumentName(instrument.getName())
                            .signalType(type)
                            .currentPrice(currentPrice)
                            .rsiValues(rsiValues)
                            .timeframesAligned(obCount)
                            .totalTimeframes(rsiValues.size())
                            .signalStrength(strength)
                            .triggerCandle(triggerCandle)
                            .build();

                    log.info("TREND_BUY_DIP: {} RSI dipped to {} in strong uptrend ({} consecutive OB signals)",
                            instrument.getName(), fastRsi, obCount);
                    eventPublisher.publishEvent(new SignalEvent(this, signal));
                    cooldownService.recordAlert(instrument.getSymbol(), type);
                }
            }
        } else if (trend == TrendState.STRONG_DOWNTREND) {
            // Sell the rally: fastest RSI has bounced into the rally zone
            if (fastRsi >= rallyRsiLower && fastRsi <= rallyRsiUpper) {
                SignalLog.SignalType type = SignalLog.SignalType.TREND_SELL_RALLY;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int osCount = consecutiveOversold.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(50 - fastRsi).abs()
                            .setScale(4, RoundingMode.HALF_UP);

                    RsiSignal signal = RsiSignal.builder()
                            .symbol(instrument.getSymbol())
                            .instrumentName(instrument.getName())
                            .signalType(type)
                            .currentPrice(currentPrice)
                            .rsiValues(rsiValues)
                            .timeframesAligned(osCount)
                            .totalTimeframes(rsiValues.size())
                            .signalStrength(strength)
                            .triggerCandle(triggerCandle)
                            .build();

                    log.info("TREND_SELL_RALLY: {} RSI rallied to {} in strong downtrend ({} consecutive OS signals)",
                            instrument.getName(), fastRsi, osCount);
                    eventPublisher.publishEvent(new SignalEvent(this, signal));
                    cooldownService.recordAlert(instrument.getSymbol(), type);
                }
            }
        }
    }

    private BigDecimal getFastestRsi(Map<String, BigDecimal> rsiValues, String timeframesConfig) {
        String fastestTf = Arrays.stream(timeframesConfig.split(","))
                .map(String::trim)
                .min(Comparator.comparingInt(this::timeframeToMinutes))
                .orElse(null);
        if (fastestTf == null) return null;
        return rsiValues.get(fastestTf);
    }

    private int timeframeToMinutes(String tf) {
        return switch (tf.toLowerCase()) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "10m" -> 10;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "2h"  -> 120;
            case "4h"  -> 240;
            case "1d"  -> 1440;
            default    -> 9999;
        };
    }
}
