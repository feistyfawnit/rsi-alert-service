package com.trading.rsi.service;

import com.trading.rsi.config.AnomalyProperties;
import com.trading.rsi.domain.AnomalyLog;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import com.trading.rsi.repository.AnomalyLogRepository;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnomalyNotificationService {

    private final AppSettingsService appSettingsService;
    private final TelegramNotificationService telegramNotificationService;
    private final SignalLogRepository signalLogRepository;
    private final AnomalyLogRepository anomalyLogRepository;
    private final AnomalyProperties anomalyProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;

    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;

    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @EventListener
    @Async
    public void handleAnomalyEvent(AnomalyEvent event) {
        AnomalyAlert alert = event.getAlert();

        Object symbolObj = alert.getDetails() != null ? alert.getDetails().get("symbol") : null;
        String symbol = symbolObj instanceof String s ? s : null;
        anomalyLogRepository.save(AnomalyLog.builder()
                .type(alert.getType())
                .severity(alert.getSeverity())
                .symbol(symbol)
                .description(alert.getDescription())
                .detectedAt(alert.getDetectedAt())
                .build());

        // 1. Correlation burst collapse — if this individual alert is part of a
        //    multi-symbol burst, emit one CROSS_CORRELATION and suppress individuals.
        //    Applies to VOLUME_SPIKE only (Polymarket / correlation don't self-loop).
        if (alert.getType() == AnomalyAlert.AnomalyType.VOLUME_SPIKE
                && maybeEmitCorrelationBurst(alert, symbol)) {
            log.info("Suppressed individual anomaly for {} — part of correlation burst", symbol);
            return;
        }

        // 2. Notify-scope gate — only Telegram is filtered; DB log is unaffected.
        if (!shouldNotify(alert, symbol)) {
            log.debug("Notify-scope {} — suppressing {} anomaly for {} (not relevant)",
                    anomalyProperties.getVolumeSpike().getNotifyScope(), alert.getSeverity(), symbol);
            return;
        }

        // 3. Quiet hours — CRITICAL always bypasses.
        if (alert.getSeverity() != AnomalyAlert.Severity.CRITICAL && isQuietHours()) {
            log.debug("Quiet hours — suppressing {} anomaly alert for {}", alert.getSeverity(), alert.getMarket());
            return;
        }
        sendUrgentAlert(alert);
    }

    /**
     * Notify-scope gate. Only affects Telegram push; the alert is always stored in
     * anomaly_log. Also always notifies for CROSS_CORRELATION (system-level) regardless
     * of the symbol-level scope.
     */
    private boolean shouldNotify(AnomalyAlert alert, String symbol) {
        if (alert.getType() == AnomalyAlert.AnomalyType.CROSS_CORRELATION) return true;
        if (alert.getType() == AnomalyAlert.AnomalyType.POLYMARKET_ODDS_SHIFT) return true;
        AnomalyProperties.NotifyScope scope = anomalyProperties.getVolumeSpike().getNotifyScope();
        if (scope == null || scope == AnomalyProperties.NotifyScope.ALL) return true;
        if (symbol == null) return true;  // can't filter without a symbol — fail open.

        boolean hasPosition = hasOpenPositionFor(alert);
        if (scope == AnomalyProperties.NotifyScope.OPEN_ONLY) return hasPosition;
        if (scope == AnomalyProperties.NotifyScope.RELEVANT) {
            if (hasPosition) return true;
            int hours = anomalyProperties.getVolumeSpike().getNotifyRecentSignalHours();
            return signalLogRepository.findFirstBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(
                    symbol, LocalDateTime.now().minusHours(hours)).isPresent();
        }
        return true;
    }

    /**
     * Correlation burst detector. If ≥ minInstruments distinct symbols have fired anomalies
     * within windowSeconds AND we haven't already emitted a CROSS_CORRELATION for this
     * window, emit one and return true so the caller suppresses the individual alert.
     * Returns true only when THIS alert is the one that triggered (or is part of) a live
     * burst — so all subsequent individuals in the same burst get suppressed.
     */
    private boolean maybeEmitCorrelationBurst(AnomalyAlert alert, String symbol) {
        AnomalyProperties.CorrelationConfig cfg = anomalyProperties.getCorrelation();
        if (cfg == null || !cfg.isEnabled()) return false;
        if (symbol == null) return false;

        Instant since = Instant.now().minusSeconds(cfg.getWindowSeconds());
        List<String> distinct = anomalyLogRepository.findDistinctSymbolsSince(since);
        if (distinct.size() < cfg.getMinInstruments()) return false;

        boolean alreadyEmitted = anomalyLogRepository.existsCorrelationSince(since);
        if (!alreadyEmitted) {
            AnomalyAlert correlation = AnomalyAlert.builder()
                    .type(AnomalyAlert.AnomalyType.CROSS_CORRELATION)
                    .severity(AnomalyAlert.Severity.CRITICAL)
                    .market(String.format("%d instruments in %ds", distinct.size(), cfg.getWindowSeconds()))
                    .description(String.format(
                            "Systemic event — %d symbols firing simultaneously: %s. Likely macro / news. Individual anomaly alerts suppressed.",
                            distinct.size(), String.join(", ", distinct)))
                    .detectedAt(Instant.now())
                    .details(Map.of(
                            "symbols", distinct,
                            "windowSeconds", cfg.getWindowSeconds()
                    ))
                    .build();
            log.warn("CROSS_CORRELATION burst: {} symbols in {}s — {}",
                    distinct.size(), cfg.getWindowSeconds(), distinct);
            eventPublisher.publishEvent(new AnomalyEvent(this, correlation));
        }
        return true;
    }

    /**
     * Returns true if a CRITICAL anomaly was logged for this symbol in the last windowMinutes.
     * Used by SignalDetectionService to suppress signals during high-uncertainty events.
     */
    public boolean recentCriticalAnomalyFor(String symbol, int windowMinutes) {
        Instant since = Instant.now().minusSeconds(windowMinutes * 60L);
        return anomalyLogRepository.existsBySymbolAndDetectedAtAfter(symbol, since)
                || anomalyLogRepository.existsBySeverityAndDetectedAtAfter(AnomalyAlert.Severity.CRITICAL, since);
    }

    private Optional<SignalLog> findRecentSignal(String symbol) {
        return signalLogRepository.findFirstBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(
                symbol, LocalDateTime.now().minusHours(6));
    }

    private String signalDirection(SignalLog.SignalType type) {
        return switch (type) {
            case OVERSOLD, PARTIAL_OVERSOLD, WATCH_OVERSOLD, TREND_BUY_DIP -> "LONG";
            case OVERBOUGHT, PARTIAL_OVERBOUGHT, WATCH_OVERBOUGHT, TREND_SELL_RALLY -> "SHORT";
        };
    }

    private boolean hasOpenPositionFor(AnomalyAlert alert) {
        String activePosition = appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "");
        if (activePosition == null || activePosition.isBlank()) return false;
        Object symbol = alert.getDetails().get("symbol");
        return activePosition.equalsIgnoreCase(String.valueOf(symbol));
    }

    private boolean isQuietHours() {
        if (!quietHoursEnabled) return false;
        int hour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        return quietHoursStart > quietHoursEnd
                ? hour >= quietHoursStart || hour < quietHoursEnd
                : hour >= quietHoursStart && hour < quietHoursEnd;
    }

    private void sendUrgentAlert(AnomalyAlert alert) {
        String title = buildTitle(alert);
        String message = buildMessage(alert);
        telegramNotificationService.send(title, message);
    }

    private String buildTitle(AnomalyAlert alert) {
        String prefix = alert.getSeverity() == AnomalyAlert.Severity.CRITICAL
                ? "🚨 CRITICAL ANOMALY" : "⚠️ ANOMALY DETECTED";
        return prefix + " — " + alert.getMarket();
    }

    private String buildMessage(AnomalyAlert alert) {
        StringBuilder sb = new StringBuilder();
        sb.append(alert.getDescription()).append("\n\n");

        switch (alert.getType()) {
            case POLYMARKET_ODDS_SHIFT -> {
                Object shiftObj = alert.getDetails().get("shiftPp");
                Object probObj = alert.getDetails().get("yesProbabilityPct");
                Object windowObj = alert.getDetails().get("windowMinutes");
                if (shiftObj instanceof Number && probObj instanceof Number && windowObj instanceof Number) {
                    double shift = ((Number) shiftObj).doubleValue();
                    double prob = ((Number) probObj).doubleValue();
                    int window = ((Number) windowObj).intValue();
                    sb.append(String.format("Odds shift: +%.1fpp in %d min%n", shift, window));
                    sb.append(String.format("Current YES probability: %.1f%%%n", prob));
                }
                sb.append("Correlated instruments may move — review open positions.\n");
            }
            case VOLUME_SPIKE -> {
                Object zObj = alert.getDetails().get("zScore");
                Object volObj = alert.getDetails().get("currentVolume");
                Object meanObj = alert.getDetails().get("baselineMean");
                Object dirObj = alert.getDetails().get("direction");
                if (volObj instanceof Number && meanObj instanceof Number && zObj instanceof Number) {
                    double z = ((Number) zObj).doubleValue();
                    double vol = ((Number) volObj).doubleValue();
                    double mean = ((Number) meanObj).doubleValue();
                    sb.append(String.format("Volume: %.0f vs baseline %.0f%n", vol, mean));
                    sb.append(String.format("Z-score: %.1f\u03c3 above normal%n", z));
                }
                if (dirObj instanceof String dir) {
                    switch (dir) {
                        case "bullish" -> sb.append("📈 Direction: BULLISH candle — price rose on spike (buy pressure / breakout)\n");
                        case "bearish" -> sb.append("📉 Direction: BEARISH candle — price fell on spike (sell pressure / panic)\n");
                        default -> sb.append("\u27A1\uFE0F Direction: Neutral candle — direction unclear\n");
                    }
                }
                Object symbolObj = alert.getDetails().get("symbol");
                if (symbolObj instanceof String sym) {
                    Optional<SignalLog> recent = findRecentSignal(sym);
                    if (recent.isPresent()) {
                        SignalLog sig = recent.get();
                        long hoursAgo = ChronoUnit.HOURS.between(sig.getCreatedAt(), LocalDateTime.now());
                        String recDir = signalDirection(sig.getSignalType());
                        String spikeDir = dirObj instanceof String d ? d : "";
                        boolean aligns = ("LONG".equals(recDir) && "bullish".equals(spikeDir))
                                || ("SHORT".equals(recDir) && "bearish".equals(spikeDir));
                        sb.append(aligns
                                ? String.format("\n\uD83D\uDCA1 Recent <b>%s</b> signal %dh ago \u2014 spike <b>confirms</b> \u2705\n", recDir, hoursAgo)
                                : String.format("\n\u26A0\uFE0F Recent <b>%s</b> signal %dh ago \u2014 spike contradicts \u274C\n", recDir, hoursAgo));
                    }
                }
            }
            case CROSS_CORRELATION -> {
                sb.append("Multiple anomaly signals coinciding — elevated manipulation risk.\n");
            }
        }

        if (hasOpenPositionFor(alert)) {
            sb.append("\n🏦 YOU HAVE AN OPEN POSITION in this instrument — review immediately.");
        }
        sb.append("\n⏸ Pause any pending automated trades until picture clears.");
        if (alert.getSeverity() == AnomalyAlert.Severity.CRITICAL) {
            sb.append("\n🔴 CRITICAL: Consider closing open positions immediately.");
        }

        return sb.toString();
    }
}
