package com.trading.rsi.service;

import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnomalyNotificationService {

    private final AppSettingsService appSettingsService;
    private final TelegramNotificationService telegramNotificationService;

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
        String activePosition = appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "");
        if (activePosition == null || activePosition.isBlank()) {
            log.debug("No active position — suppressing anomaly alert for {}", alert.getMarket());
            return;
        }
        if (alert.getSeverity() != AnomalyAlert.Severity.CRITICAL && isQuietHours()) {
            log.debug("Quiet hours — suppressing {} anomaly alert for {}", alert.getSeverity(), alert.getMarket());
            return;
        }
        sendUrgentAlert(alert);
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
                        case "bullish" -> sb.append("\uD83D\uDCC8 Direction: BULLISH candle — price rose on spike (buy pressure / breakout)\n");
                        case "bearish" -> sb.append("\uD83D\uDCC9 Direction: BEARISH candle — price fell on spike (sell pressure / panic)\n");
                        default -> sb.append("\u27A1\uFE0F Direction: Neutral candle — direction unclear\n");
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
