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
 * Detects strong trends using two methods (primary + fallback) and generates
 * TREND_BUY_DIP / TREND_SELL_RALLY signals when price pulls back within a confirmed trend.
 *
 * PRIMARY filter — EMA 50 on the 1h timeframe:
 *   If price > EMA50(1h) → STRONG_UPTREND. Suppress OVERBOUGHT sell signals immediately.
 *   If price < EMA50(1h) → STRONG_DOWNTREND. Suppress OVERSOLD buy signals immediately.
 *
 * FALLBACK filter — consecutive signal count (used when 1h EMA history not yet available):
 *   After 2+ consecutive OVERBOUGHT full signals → STRONG_UPTREND.
 *   After 2+ consecutive OVERSOLD full signals → STRONG_DOWNTREND.
 *
 * Dip entry (TREND_BUY_DIP):
 *   Triggered when fastest-TF RSI drops below 60 (pulled back from overbought >70)
 *   while price is still above EMA50(1h). This is the classic buy-the-dip setup.
 *
 * Rally entry (TREND_SELL_RALLY):
 *   Triggered when fastest-TF RSI bounces above 40 while price is below EMA50(1h).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendDetectionService {

    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;
    private final EmaCalculator emaCalculator;
    private final PriceHistoryService priceHistoryService;

    @Value("${rsi.trend.ema-period:50}")
    private int emaPeriod;

    @Value("${rsi.trend.ema-timeframe:1h}")
    private String emaTrendTimeframe;

    @Value("${rsi.trend.consecutive-signals-for-trend:2}")
    private int consecutiveSignalsForTrend;

    @Value("${rsi.trend.dip-rsi-threshold:60}")
    private double dipRsiThreshold;

    @Value("${rsi.trend.rally-rsi-threshold:40}")
    private double rallyRsiThreshold;

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
     * PRIMARY: checks EMA 50 on 1h timeframe.
     * FALLBACK: consecutive signal count if EMA data unavailable.
     */
    public TrendState getTrendState(String symbol) {
        // PRIMARY: EMA 50 on the configured trend timeframe
        Boolean aboveEma = getPriceVsEma(symbol);
        if (aboveEma != null) {
            return aboveEma ? TrendState.STRONG_UPTREND : TrendState.STRONG_DOWNTREND;
        }

        // FALLBACK: consecutive signal count
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
     * Compute price vs EMA50 for the trend timeframe.
     * Returns true = price above EMA (uptrend), false = below (downtrend), null = insufficient data.
     */
    private Boolean getPriceVsEma(String symbol) {
        String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() < emaPeriod) return null;
        Boolean above = emaCalculator.isPriceAboveEma(history, emaPeriod);
        if (above != null) {
            BigDecimal ema = emaCalculator.calculate(history, emaPeriod);
            log.debug("EMA{} ({}) for {}: {} | price {} EMA",
                    emaPeriod, emaTrendTimeframe, symbol, ema,
                    above ? ">" : "<");
        }
        return above;
    }

    /**
     * Returns a human-readable explanation of what is driving the current trend classification.
     * Used in Telegram notifications so the user understands why a signal was suppressed or generated.
     */
    public String getTrendReason(String symbol) {
        Boolean aboveEma = getPriceVsEma(symbol);
        if (aboveEma != null) {
            return String.format("price %s EMA%d(%s)", aboveEma ? ">" : "<", emaPeriod, emaTrendTimeframe);
        }
        int obCount = consecutiveOverbought.getOrDefault(symbol, 0);
        int osCount = consecutiveOversold.getOrDefault(symbol, 0);
        int count = Math.max(obCount, osCount);
        return String.format("%d consecutive full signals (EMA data building up)", count);
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
     * Whether to suppress a counter-trend signal.
     * OVERBOUGHT suppressed in uptrend; OVERSOLD suppressed in downtrend.
     * Logs the reason (EMA-based or consecutive-based).
     */
    public boolean shouldSuppressCounterTrend(String symbol, SignalLog.SignalType signalType) {
        if (!suppressCounterTrend) return false;

        TrendState trend = getTrendState(symbol);
        boolean emaAvailable = getPriceVsEma(symbol) != null;
        String reason = emaAvailable
                ? String.format("price vs EMA%d(%s)", emaPeriod, emaTrendTimeframe)
                : String.format("%d consecutive signals", consecutiveOverbought.getOrDefault(symbol,
                    consecutiveOversold.getOrDefault(symbol, 0)));

        if (trend == TrendState.STRONG_UPTREND && signalType == SignalLog.SignalType.OVERBOUGHT) {
            log.info("Suppressing OVERBOUGHT sell signal for {} — strong uptrend ({}) — consider BUY THE DIP instead",
                    symbol, reason);
            return true;
        }
        if (trend == TrendState.STRONG_DOWNTREND && signalType == SignalLog.SignalType.OVERSOLD) {
            log.info("Suppressing OVERSOLD buy signal for {} — strong downtrend ({}) — consider SELL THE RALLY instead",
                    symbol, reason);
            return true;
        }
        return false;
    }

    /**
     * Check if RSI values indicate a dip in an uptrend (buy opportunity)
     * or a rally in a downtrend (sell opportunity).
     *
     * TREND_BUY_DIP: fastest TF RSI has pulled back below dipRsiThreshold (default 60)
     *   from an overbought level, while price is still above EMA50(1h). This means the
     *   short-term momentum cooled but the trend is intact — classic buy-the-dip.
     *
     * TREND_SELL_RALLY: fastest TF RSI bounced above rallyRsiThreshold (default 40)
     *   while price is still below EMA50(1h). Short-term bounce in a downtrend.
     *
     * Called on every poll cycle.
     */
    public void checkForTrendEntry(Instrument instrument, Map<String, BigDecimal> rsiValues,
                                    BigDecimal currentPrice, Candle triggerCandle) {
        TrendState trend = getTrendState(instrument.getSymbol());
        if (trend == TrendState.NEUTRAL) return;

        BigDecimal fastestRsi = getFastestRsi(rsiValues, instrument.getTimeframes());
        if (fastestRsi == null) return;
        double fastRsi = fastestRsi.doubleValue();

        if (trend == TrendState.STRONG_UPTREND) {
            // RSI pulled back below threshold (cooled from overbought) — dip in uptrend
            if (fastRsi < dipRsiThreshold && fastRsi > 30) {
                SignalLog.SignalType type = SignalLog.SignalType.TREND_BUY_DIP;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int obCount = consecutiveOverbought.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(dipRsiThreshold - fastRsi)
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

                    log.info("TREND_BUY_DIP: {} RSI={} pulled back below {} in uptrend — price {} EMA{}({})",
                            instrument.getName(), String.format("%.1f", fastRsi), dipRsiThreshold,
                            currentPrice, emaPeriod, emaTrendTimeframe);
                    eventPublisher.publishEvent(new SignalEvent(this, signal));
                    cooldownService.recordAlert(instrument.getSymbol(), type);
                }
            }
        } else if (trend == TrendState.STRONG_DOWNTREND) {
            // RSI bounced above threshold (cooled from oversold) — rally in downtrend
            if (fastRsi > rallyRsiThreshold && fastRsi < 70) {
                SignalLog.SignalType type = SignalLog.SignalType.TREND_SELL_RALLY;
                if (cooldownService.shouldAlert(instrument.getSymbol(), type)) {
                    int osCount = consecutiveOversold.getOrDefault(instrument.getSymbol(), 0);
                    BigDecimal strength = BigDecimal.valueOf(fastRsi - rallyRsiThreshold)
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

                    log.info("TREND_SELL_RALLY: {} RSI={} bounced above {} in downtrend — price {} EMA{}({})",
                            instrument.getName(), String.format("%.1f", fastRsi), rallyRsiThreshold,
                            currentPrice, emaPeriod, emaTrendTimeframe);
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
