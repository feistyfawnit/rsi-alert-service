package com.trading.rsi.service;

import com.trading.rsi.config.AnomalyProperties;
import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import com.trading.rsi.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class VolumeAnomalyDetector {

    private final ApplicationEventPublisher eventPublisher;
    private final AnomalyProperties anomalyProperties;

    private final Map<String, Deque<BigDecimal>> volumeHistory = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();
    private final Map<String, RecentAnomaly> lastAnomalyDetails = new ConcurrentHashMap<>();

    public void onNewCandle(String symbol, String instrumentName, String timeframe, Candle candle) {
        AnomalyProperties.VolumeSpikeConfig cfg = anomalyProperties.getVolumeSpike();
        if (!anomalyProperties.isEnabled() || !cfg.isEnabled()) return;
        if (candle.getVolume() == null || candle.getVolume().compareTo(BigDecimal.ZERO) == 0) return;

        String key = symbol + ":" + timeframe;
        Deque<BigDecimal> history = volumeHistory.computeIfAbsent(key, k -> new ArrayDeque<>());

        List<BigDecimal> baseline;
        synchronized (history) {
            history.addLast(candle.getVolume());
            while (history.size() > cfg.getLookbackPeriods()) {
                history.removeFirst();
            }
            if (history.size() < cfg.getMinPeriodsBeforeAlert()) return;
            baseline = new ArrayList<>(history);
        }
        baseline.remove(baseline.size() - 1);

        double mean = baseline.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (mean == 0) return;
        if (mean < cfg.getMinBaselineVolume()) {
            log.debug("Skipping anomaly check for {}:{} — baseline volume too low ({} < {})",
                    symbol, timeframe, String.format("%.1f", mean), String.format("%.1f", cfg.getMinBaselineVolume()));
            return;
        }

        double variance = baseline.stream()
                .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        if (stdDev == 0) return;

        double currentVolume = candle.getVolume().doubleValue();
        String direction = (candle.getClose() != null && candle.getOpen() != null)
                ? (candle.getClose().compareTo(candle.getOpen()) > 0 ? "bullish"
                   : candle.getClose().compareTo(candle.getOpen()) < 0 ? "bearish" : "neutral")
                : "unknown";

        double zScore = (currentVolume - mean) / stdDev;

        log.debug("Volume [{}:{}] current={} mean={} z={}",
                symbol, timeframe, String.format("%.0f", currentVolume),
                String.format("%.0f", mean), String.format("%.2f", zScore));

        if (zScore >= cfg.getStdDevThreshold()) {
            Instant now = Instant.now();
            Instant cooldownBoundary = now.minus(cfg.getCooldownMinutes(), ChronoUnit.MINUTES);
            // Apr 24 2026: dedupe cooldown key changed from `symbol:timeframe` to just `symbol`.
            // A single news event closes candles on 15m and 30m within seconds of each other;
            // under the old key both fired independently. Now the higher-σ TF wins and the
            // others are suppressed for `cooldownMinutes`. Baseline history remains per-TF.
            boolean[] claimed = {false};
            lastAlertTime.compute(symbol, (k, prev) -> {
                if (prev == null || prev.isBefore(cooldownBoundary)) {
                    claimed[0] = true;
                    return now;
                }
                return prev;
            });

            if (claimed[0]) {
                AnomalyAlert.Severity severity = zScore >= cfg.getStdDevThreshold() * 1.5
                        ? AnomalyAlert.Severity.CRITICAL : AnomalyAlert.Severity.HIGH;

                AnomalyAlert alert = Objects.requireNonNull(AnomalyAlert.builder()
                        .type(AnomalyAlert.AnomalyType.VOLUME_SPIKE)
                        .severity(severity)
                        .market(instrumentName + " (" + timeframe + ")")
                        .description(String.format("Volume spike %.1f\u03c3 above baseline — %s %s (%s candle)",
                                zScore, instrumentName, timeframe, direction))
                        .detectedAt(now)
                        .details(Map.of(
                                "symbol", symbol,
                                "timeframe", timeframe,
                                "currentVolume", currentVolume,
                                "baselineMean", mean,
                                "stdDev", stdDev,
                                "zScore", zScore,
                                "direction", direction
                        ))
                        .build());

                log.warn("VOLUME ANOMALY: {} {} — {}σ (vol={} vs mean={})",
                        symbol, timeframe, String.format("%.1f", zScore),
                        String.format("%.0f", currentVolume), String.format("%.0f", mean));
                eventPublisher.publishEvent(new AnomalyEvent(this, alert));
                lastAnomalyDetails.put(key, new RecentAnomaly(direction, zScore, now));
            }
        }
    }

    /**
     * Returns the most recent volume anomaly for a symbol across all its timeframes,
     * if it occurred within the specified window (e.g., last 2 hours).
     * Used to enrich signal messages with volume context before entry.
     */
    public Optional<RecentAnomaly> getRecentAnomaly(String symbol, int lookbackHours) {
        Instant cutoff = Instant.now().minus(lookbackHours, ChronoUnit.HOURS);
        return lastAnomalyDetails.entrySet().stream()
                .filter(e -> e.getKey().startsWith(symbol + ":"))
                .filter(e -> e.getValue().detectedAt().isAfter(cutoff))
                .max(Comparator.comparing(e -> e.getValue().detectedAt()))
                .map(Map.Entry::getValue);
    }

    public record RecentAnomaly(String direction, double zScore, Instant detectedAt) {}
}

