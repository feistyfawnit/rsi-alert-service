package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks PARTIAL signals and actively monitors the lagging timeframe.
 * When 2/3 timeframes are aligned, this service:
 *  1. Sends periodic follow-up notifications showing whether the gap is closing or widening
 *  2. Fires an urgent "FULL ALIGNMENT" alert if the lagging TF crosses the threshold
 *  3. Sends an "expired" notification when the monitoring window closes
 *
 * This addresses the 4h lag problem documented in PROJECT_LOG.md —
 * indices like DAX can V-recover before the 4h RSI catches up.
 */
@Service
@Slf4j
public class PartialSignalMonitorService {

    private final NotificationService notificationService;

    @Value("${rsi.partial-monitoring.enabled:true}")
    private boolean enabled;

    @Value("${rsi.partial-monitoring.window-minutes:60}")
    private int windowMinutes;

    @Value("${rsi.partial-monitoring.follow-up-interval-minutes:30}")
    private int followUpIntervalMinutes;

    @Value("${rsi.partial-monitoring.max-initial-gap:8}")
    private double maxInitialGap;

    private final Map<String, ActivePartial> activePartials = new ConcurrentHashMap<>();

    public PartialSignalMonitorService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Called by SignalDetectionService when a PARTIAL signal is first detected.
     */
    public void registerPartial(String symbol, String instrumentName,
                                SignalLog.SignalType signalType,
                                String laggingTimeframe, BigDecimal laggingRsi,
                                double threshold) {
        if (!enabled) return;

        // Don't re-register if already monitoring this symbol
        if (activePartials.containsKey(symbol)) {
            log.debug("Already actively monitoring {} — skipping re-register", symbol);
            return;
        }

        BigDecimal gap = laggingRsi.subtract(BigDecimal.valueOf(threshold)).abs();
        if (gap.doubleValue() > maxInitialGap) {
            log.debug("Suppressing partial for {} — lagging {} RSI {} is {} pts from threshold {} (max gap: {})",
                    instrumentName, laggingTimeframe, laggingRsi.setScale(2, RoundingMode.HALF_UP),
                    String.format("%.1f", gap.doubleValue()), (int) threshold, maxInitialGap);
            return;
        }
        activePartials.put(symbol, ActivePartial.builder()
                .symbol(symbol)
                .instrumentName(instrumentName)
                .signalType(signalType)
                .laggingTimeframe(laggingTimeframe)
                .threshold(threshold)
                .initialGap(gap)
                .lastKnownGap(gap.doubleValue())
                .startTime(Instant.now())
                .lastFollowUp(Instant.now())
                .build());

        log.info("Active partial monitoring STARTED for {} — watching {} RSI {} (gap: {} pts, window: {} min)",
                instrumentName, laggingTimeframe, laggingRsi.setScale(2, RoundingMode.HALF_UP),
                gap.setScale(2, RoundingMode.HALF_UP), windowMinutes);
    }

    /**
     * Called on every poll cycle for instruments that have an active partial.
     * Checks whether the lagging TF has crossed, sends follow-ups, or expires.
     */
    public void updatePartials(String symbol, Map<String, BigDecimal> rsiValues) {
        if (!enabled) return;

        ActivePartial partial = activePartials.get(symbol);
        if (partial == null) return;

        // Check expiry
        if (Instant.now().isAfter(partial.getStartTime().plus(Duration.ofMinutes(windowMinutes)))) {
            BigDecimal currentRsi = rsiValues.get(partial.getLaggingTimeframe());
            log.info("Active partial monitoring EXPIRED for {} after {} min", symbol, windowMinutes);
            activePartials.remove(symbol);
            log.info("Partial expired (no notification) — {} {} RSI: {} vs threshold {}",
                    partial.getInstrumentName(), partial.getLaggingTimeframe(),
                    currentRsi != null ? currentRsi.setScale(2, RoundingMode.HALF_UP) : "unknown",
                    (int) partial.getThreshold());
            return;
        }

        BigDecimal laggingRsi = rsiValues.get(partial.getLaggingTimeframe());
        if (laggingRsi == null) return;

        boolean isOversold = partial.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD;
        boolean nowAligned = isOversold
                ? laggingRsi.doubleValue() < partial.getThreshold()
                : laggingRsi.doubleValue() > partial.getThreshold();

        if (nowAligned) {
            log.info("PARTIAL -> FULL CONVERSION for {} — {} RSI {} crossed threshold {}!",
                    partial.getInstrumentName(), partial.getLaggingTimeframe(),
                    laggingRsi.setScale(2, RoundingMode.HALF_UP), (int) partial.getThreshold());
            activePartials.remove(symbol);
            if (notificationService.getMutedSymbols().contains(symbol)) {
                log.info("Symbol {} is muted — FULL ALIGNMENT occurred but suppressed", symbol);
                return;
            }
            notificationService.sendRawNotification(
                    partial.getInstrumentName() + " FULL ALIGNMENT!",
                    partial.getLaggingTimeframe() + " RSI now "
                            + laggingRsi.setScale(2, RoundingMode.HALF_UP)
                            + " (crossed " + (isOversold ? "<" : ">") + (int) partial.getThreshold() + ")\n"
                            + "All timeframes now aligned!\n\n"
                            + "This was a tracked partial — check chart and consider entry.",
                    "urgent",
                    isOversold ? "chart_with_upwards_trend,rotating_light"
                               : "chart_with_downwards_trend,rotating_light");
            return;
        }

        // Periodic follow-up — only send when gap is actively closing
        double currentGap = isOversold
                ? laggingRsi.doubleValue() - partial.getThreshold()
                : partial.getThreshold() - laggingRsi.doubleValue();

        Duration sinceLastFollowUp = Duration.between(partial.getLastFollowUp(), Instant.now());
        if (sinceLastFollowUp.toMinutes() >= followUpIntervalMinutes) {
            partial.setLastFollowUp(Instant.now());
            boolean gapClosing = currentGap < partial.getLastKnownGap();
            partial.setLastKnownGap(currentGap);
            if (!gapClosing) {
                log.debug("Partial follow-up for {} skipped — gap widening/static ({} pts)", partial.getSymbol(), String.format("%.1f", currentGap));
                return;
            }
            if (notificationService.getMutedSymbols().contains(partial.getSymbol())) {
                log.debug("Symbol {} is muted — skipping partial follow-up", partial.getSymbol());
                return;
            }
            if (notificationService.isNoTradeModeActive()) {
                log.debug("No-trade mode active — skipping partial follow-up for {}", partial.getSymbol());
                return;
            }
            long minutesActive = Duration.between(partial.getStartTime(), Instant.now()).toMinutes();
            long minutesRemaining = windowMinutes - minutesActive;

            notificationService.sendRawNotification(
                    partial.getInstrumentName() + " Partial Closing In",
                    partial.getLaggingTimeframe() + " RSI: "
                            + laggingRsi.setScale(2, RoundingMode.HALF_UP)
                            + " -> needs " + (isOversold ? "<" : ">") + (int) partial.getThreshold()
                            + " (" + String.format("%.1f", currentGap) + " pts away, closing)\n"
                            + minutesActive + " min elapsed, " + minutesRemaining + " min remaining",
                    "default", "eyes");
        }
    }

    public boolean isActivelyMonitored(String symbol) {
        return activePartials.containsKey(symbol);
    }

    public int getActiveCount() {
        return activePartials.size();
    }

    @Data
    @Builder
    private static class ActivePartial {
        private String symbol;
        private String instrumentName;
        private SignalLog.SignalType signalType;
        private String laggingTimeframe;
        private double threshold;
        private BigDecimal initialGap;
        private double lastKnownGap;
        private Instant startTime;
        private Instant lastFollowUp;
    }
}
