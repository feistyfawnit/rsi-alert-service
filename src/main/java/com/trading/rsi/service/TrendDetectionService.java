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
 * PRIMARY filter — EMA 20 on the configured trend timeframe:
 *   If price > EMA20(1h) → STRONG_UPTREND. Suppress OVERBOUGHT sell signals immediately.
 *   If price < EMA20(1h) → STRONG_DOWNTREND. Suppress OVERSOLD buy signals immediately.
 *
 * FALLBACK 1 — Price momentum (used when EMA history &lt; 20 candles):
 *   If price moved &gt;1% over last 5 candles on the trend timeframe → STRONG_UPTREND / STRONG_DOWNTREND.
 *   Requires only 6 candles of history — available immediately after warmup.
 *
 * FALLBACK 2 — Consecutive signal count (last resort):
 *   After 2+ consecutive OVERBOUGHT full signals → STRONG_UPTREND.
 *   After 2+ consecutive OVERSOLD full signals → STRONG_DOWNTREND.
 *
 * Dip entry (TREND_BUY_DIP):
 *   Triggered when fastest-TF RSI drops below 60 (pulled back from overbought >70)
 *   while price is still above EMA20(1h). This is the classic buy-the-dip setup.
 *
 * Rally entry (TREND_SELL_RALLY):
 *   Triggered when fastest-TF RSI bounces above 40 while price is below EMA20(1h).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrendDetectionService {

    private final ApplicationEventPublisher eventPublisher;
    private final SignalCooldownService cooldownService;
    private final EmaCalculator emaCalculator;
    private final PriceHistoryService priceHistoryService;

    @Value("${rsi.trend.ema-period:20}")
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

    private static final int MOMENTUM_LOOKBACK = 5;
    private static final double MOMENTUM_THRESHOLD_PCT = 1.0;

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
     * PRIMARY: EMA 20 on 1h timeframe.
     * FALLBACK 1: Price momentum over last MOMENTUM_LOOKBACK candles.
     * FALLBACK 2: Consecutive signal count if both EMA and momentum unavailable.
     */
    public TrendState getTrendState(String symbol) {
        // PRIMARY: EMA 20 on the configured trend timeframe
        Boolean aboveEma = getPriceVsEma(symbol);
        if (aboveEma != null) {
            return aboveEma ? TrendState.STRONG_UPTREND : TrendState.STRONG_DOWNTREND;
        }

        // FALLBACK 1: Price momentum — needs only MOMENTUM_LOOKBACK + 1 candles
        double changePct = getMomentumChangePct(symbol);
        if (!Double.isNaN(changePct)) {
            if (changePct > MOMENTUM_THRESHOLD_PCT) return TrendState.STRONG_UPTREND;
            if (changePct < -MOMENTUM_THRESHOLD_PCT) return TrendState.STRONG_DOWNTREND;
        }

        // FALLBACK 2: consecutive signal count
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
     * Compute price vs EMA for the trend timeframe.
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
            return String.format("price %s EMA%d(%s)", aboveEma ? "above" : "below", emaPeriod, emaTrendTimeframe);
        }
        double changePct = getMomentumChangePct(symbol);
        if (!Double.isNaN(changePct) && (changePct > MOMENTUM_THRESHOLD_PCT || changePct < -MOMENTUM_THRESHOLD_PCT)) {
            return String.format("%.1f%% move in %d %s candles (momentum proxy)",
                    changePct, MOMENTUM_LOOKBACK, emaTrendTimeframe);
        }
        int obCount = consecutiveOverbought.getOrDefault(symbol, 0);
        int osCount = consecutiveOversold.getOrDefault(symbol, 0);
        int count = Math.max(obCount, osCount);
        return String.format("%d consecutive full signals", count);
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
     * Logs the reason (EMA-based, momentum-based, or consecutive-based).
     */
    public boolean shouldSuppressCounterTrend(String symbol, SignalLog.SignalType signalType) {
        if (!suppressCounterTrend) return false;

        TrendState trend = getTrendState(symbol);
        String reason = getTrendReason(symbol);

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
     *   while price remains above EMA20(1h). Higher TFs staying elevated is expected in a
     *   strong uptrend and is not a disqualifier — it confirms the trend is intact.
     *
     * TREND_SELL_RALLY: fastest TF RSI bounced above rallyRsiThreshold (default 40)
     *   while price remains below EMA20(1h). Lower TFs staying depressed confirms downtrend.
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
            // Fastest TF RSI pulled back below threshold while price remains in uptrend
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
            // Fastest TF RSI bounced above threshold while price remains in downtrend
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

    /**
     * Computes the % price change over the last MOMENTUM_LOOKBACK candles on the trend timeframe.
     * Returns Double.NaN if insufficient history is available.
     */
    private double getMomentumChangePct(String symbol) {
        String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        if (history == null || history.size() <= MOMENTUM_LOOKBACK) return Double.NaN;
        BigDecimal current = history.get(history.size() - 1);
        BigDecimal reference = history.get(history.size() - 1 - MOMENTUM_LOOKBACK);
        if (reference.compareTo(BigDecimal.ZERO) == 0) return Double.NaN;
        double changePct = current.subtract(reference)
                .divide(reference, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        log.debug("Momentum {} ({}): {}% over {} candles",
                symbol, emaTrendTimeframe, String.format("%.2f", changePct), MOMENTUM_LOOKBACK);
        return changePct;
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
