package com.trading.rsi.service;

import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AnomalyNotificationService {

    private final WebClient.Builder webClientBuilder;

    @Value("${notifications.ntfy.enabled:true}")
    private boolean ntfyEnabled;

    @Value("${notifications.ntfy.topic:rsi-alerts}")
    private String ntfyTopic;

    @Value("${notifications.ntfy.server-url:https://ntfy.sh}")
    private String ntfyServerUrl;

    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;

    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;

    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @EventListener
    @Async
    public void handleAnomalyEvent(AnomalyEvent event) {
        if (!ntfyEnabled) return;
        AnomalyAlert alert = event.getAlert();
        if (alert.getSeverity() != AnomalyAlert.Severity.CRITICAL && isQuietHours()) {
            log.debug("Quiet hours — suppressing {} anomaly alert for {}", alert.getSeverity(), alert.getMarket());
            return;
        }
        sendUrgentAlert(alert);
    }

    private boolean isQuietHours() {
        if (!quietHoursEnabled) return false;
        int hour = LocalTime.now().getHour();
        return quietHoursStart > quietHoursEnd
                ? hour >= quietHoursStart || hour < quietHoursEnd
                : hour >= quietHoursStart && hour < quietHoursEnd;
    }

    private void sendUrgentAlert(AnomalyAlert alert) {
        String title = buildTitle(alert);
        String message = buildMessage(alert);

        webClientBuilder.baseUrl(ntfyServerUrl).build()
                .post()
                .uri("/" + ntfyTopic)
                .header("Title", title)
                .header("Priority", "urgent")
                .header("Tags", "rotating_light,warning,no_entry")
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(r -> log.info("Anomaly alert sent: {}", alert.getDescription()))
                .doOnError(e -> log.error("Failed to send anomaly alert: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
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
                if (volObj instanceof Number && meanObj instanceof Number && zObj instanceof Number) {
                    double z = ((Number) zObj).doubleValue();
                    double vol = ((Number) volObj).doubleValue();
                    double mean = ((Number) meanObj).doubleValue();
                    sb.append(String.format("Volume: %.0f vs baseline %.0f%n", vol, mean));
                    sb.append(String.format("Z-score: %.1f\u03c3 above normal%n", z));
                }
            }
            case CROSS_CORRELATION -> {
                sb.append("Multiple anomaly signals coinciding — elevated manipulation risk.\n");
            }
        }

        sb.append("\n⏸ Pause any pending automated trades until picture clears.");
        if (alert.getSeverity() == AnomalyAlert.Severity.CRITICAL) {
            sb.append("\n🔴 CRITICAL: Consider closing open positions immediately.");
        }

        return sb.toString();
    }
}
