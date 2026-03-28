package com.trading.rsi.service;

import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private final WebClient.Builder webClientBuilder;
    
    @Value("${notifications.ntfy.enabled:true}")
    private boolean ntfyEnabled;
    
    @Value("${notifications.ntfy.topic:rsi-alerts}")
    private String ntfyTopic;
    
    @Value("${notifications.ntfy.server-url:https://ntfy.sh}")
    private String ntfyServerUrl;
    
    @Value("${notifications.ntfy.priority:high}")
    private String ntfyPriority;
    
    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;
    
    @Value("${rsi.quiet-hours.start-hour:2}")
    private int quietHoursStart;
    
    @Value("${rsi.quiet-hours.end-hour:6}")
    private int quietHoursEnd;
    
    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        if (!ntfyEnabled) {
            log.debug("Notifications disabled, skipping alert");
            return;
        }
        
        if (isQuietHours()) {
            log.info("Quiet hours active, skipping alert for {}", event.getSignal().getSymbol());
            return;
        }
        
        sendNtfyNotification(event.getSignal());
    }
    
    private void sendNtfyNotification(RsiSignal signal) {
        String title = buildNotificationTitle(signal);
        String message = buildNotificationMessage(signal);
        
        WebClient client = webClientBuilder.baseUrl(ntfyServerUrl).build();
        
        client.post()
                .uri("/" + ntfyTopic)
                .header("Title", title)
                .header("Priority", ntfyPriority)
                .header("Tags", getTagsForSignal(signal))
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> log.info("Notification sent for {} {}", signal.getSymbol(), signal.getSignalType()))
                .doOnError(error -> log.error("Failed to send notification: {}", error.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }
    
    private String buildNotificationTitle(RsiSignal signal) {
        String emoji = switch (signal.getSignalType()) {
            case OVERSOLD -> "🟢";
            case OVERBOUGHT -> "🔴";
            case PARTIAL_OVERSOLD -> "🟡";
            case PARTIAL_OVERBOUGHT -> "🟠";
        };
        
        String signalName = switch (signal.getSignalType()) {
            case OVERSOLD -> "BUY SIGNAL";
            case OVERBOUGHT -> "SELL SIGNAL";
            case PARTIAL_OVERSOLD -> "Partial Buy";
            case PARTIAL_OVERBOUGHT -> "Partial Sell";
        };
        
        return emoji + " " + signal.getInstrumentName() + " " + signalName;
    }
    
    private String buildNotificationMessage(RsiSignal signal) {
        StringBuilder message = new StringBuilder();
        
        message.append("Price: $").append(signal.getCurrentPrice()).append("\n");
        message.append("Alignment: ").append(signal.getTimeframesAligned())
               .append("/").append(signal.getTotalTimeframes()).append(" timeframes\n");
        message.append("Strength: ").append(signal.getSignalStrength()).append("\n\n");
        message.append("RSI Values:\n");
        
        signal.getRsiValues().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> message.append("  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue().setScale(2, BigDecimal.ROUND_HALF_UP))
                        .append("\n"));
        
        message.append("\nAction: ");
        message.append(switch (signal.getSignalType()) {
            case OVERSOLD -> "Consider LONG position";
            case OVERBOUGHT -> "Consider SHORT/EXIT position";
            case PARTIAL_OVERSOLD -> "Watch for full alignment - potential LONG setup";
            case PARTIAL_OVERBOUGHT -> "Watch for full alignment - potential SHORT/EXIT";
        });
        
        return message.toString();
    }
    
    private String getTagsForSignal(RsiSignal signal) {
        return switch (signal.getSignalType()) {
            case OVERSOLD -> "chart_with_upwards_trend,money_with_wings";
            case OVERBOUGHT -> "chart_with_downwards_trend,warning";
            case PARTIAL_OVERSOLD -> "eyes,chart_increasing";
            case PARTIAL_OVERBOUGHT -> "eyes,chart_decreasing";
        };
    }
    
    private boolean isQuietHours() {
        if (!quietHoursEnabled) {
            return false;
        }
        
        int currentHour = LocalTime.now().getHour();
        
        if (quietHoursStart < quietHoursEnd) {
            return currentHour >= quietHoursStart && currentHour < quietHoursEnd;
        } else {
            return currentHour >= quietHoursStart || currentHour < quietHoursEnd;
        }
    }
}
