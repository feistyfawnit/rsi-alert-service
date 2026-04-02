package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
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
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private final WebClient.Builder webClientBuilder;
    private final ClaudeEnrichmentService claudeEnrichmentService;
    
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
    
    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;
    
    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @Value("${rsi.demo.account-balance:10000}")
    private int demoAccountBalance;

    @Value("${rsi.demo.risk-percent:1}")
    private int demoRiskPercent;

    @Value("${rsi.demo.stop-percent-crypto:2.0}")
    private double stopPercentCrypto;

    @Value("${rsi.demo.stop-percent-index:0.5}")
    private double stopPercentIndex;

    @Value("${rsi.demo.stop-percent-commodity:1.0}")
    private double stopPercentCommodity;

    @Value("${rsi.demo.account-currency:EUR}")
    private String accountCurrency;
    
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
        
        String aiContext = claudeEnrichmentService.enrichSignal(event.getSignal());
        sendNtfyNotification(event.getSignal(), aiContext);
    }
    
    private void sendNtfyNotification(RsiSignal signal, String aiContext) {
        String title = buildNotificationTitle(signal);
        String message = buildNotificationMessage(signal, aiContext);
        
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
    
    private String buildNotificationMessage(RsiSignal signal, String aiContext) {
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
                        .append(entry.getValue().setScale(2, RoundingMode.HALF_UP))
                        .append("\n"));
        
        message.append("\nAction: ");
        message.append(switch (signal.getSignalType()) {
            case OVERSOLD -> "Consider LONG position";
            case OVERBOUGHT -> "Consider SHORT/EXIT position";
            case PARTIAL_OVERSOLD -> "Watch for full alignment - potential LONG setup";
            case PARTIAL_OVERBOUGHT -> "Watch for full alignment - potential SHORT/EXIT";
        });

        message.append("\n").append(buildDemoGuidance(signal));

        if (aiContext != null && !aiContext.isBlank()) {
            message.append("\n\n📰 AI Context:\n").append(aiContext);
        }
        
        return message.toString();
    }

    private String buildDemoGuidance(RsiSignal signal) {
        double stopPct = inferStopPercent(signal.getSymbol());
        boolean isLong = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD;
        boolean isPartial = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT;
        boolean isCrypto = !signal.getSymbol().startsWith("IX.") && !signal.getSymbol().startsWith("CS.")
                && !signal.getSymbol().startsWith("CC.");

        BigDecimal entry = signal.getCurrentPrice();
        long stopPts = Math.round(entry.doubleValue() * stopPct / 100.0);
        long limitPts = stopPts * 2;

        BigDecimal riskAmount = BigDecimal.valueOf(demoAccountBalance)
                .multiply(BigDecimal.valueOf(demoRiskPercent / 100.0))
                .setScale(2, RoundingMode.HALF_UP);
        String sizePerPoint = stopPts > 0
                ? riskAmount.divide(BigDecimal.valueOf(stopPts), 2, RoundingMode.HALF_UP).toPlainString()
                : "n/a";

        String direction = isLong ? "GO LONG (BUY)" : "GO SHORT (SELL)";
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 Demo Guidance (Spread Bet):\n");
        if (isPartial) {
            sb.append("Status: Watching — ").append(signal.getTimeframesAligned())
              .append("/").append(signal.getTotalTimeframes()).append(" TFs aligned, not full yet\n");
            sb.append("If confirmed: ").append(direction).append("\n");
        } else {
            sb.append("Direction: ").append(direction).append("\n");
        }
        sb.append("Size: ").append(sizePerPoint).append(" ").append(accountCurrency)
          .append("/pt (max loss ").append(accountCurrency).append(" ").append(riskAmount.toPlainString()).append(")\n");
        sb.append("Stop: ").append(stopPts).append(" pts away (").append(stopPct).append("% below entry)\n");
        sb.append("Limit: ").append(limitPts).append(" pts away (2:1 — profit ").append(accountCurrency)
          .append(" ").append(riskAmount.multiply(java.math.BigDecimal.valueOf(2)).toPlainString()).append(")\n");
        if (isCrypto) {
            sb.append("⚠️ Crypto price is USD — recalc stop: IG entry x ").append(stopPct).append("%\n");
        }
        if (isPartial) {
            sb.append("⏳ Wait for ").append(signal.getTotalTimeframes()).append("/")
              .append(signal.getTotalTimeframes()).append(" TF alignment before entering");
        } else {
            sb.append("✅ Confirm on chart, then place on IG demo");
        }
        return sb.toString();
    }

    private double inferStopPercent(String symbol) {
        if (symbol.startsWith("IX.")) return stopPercentIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return stopPercentCommodity;
        return stopPercentCrypto;
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
        
        return quietHoursStart > quietHoursEnd
                ? currentHour >= quietHoursStart || currentHour < quietHoursEnd
                : currentHour >= quietHoursStart && currentHour < quietHoursEnd;
    }
}
