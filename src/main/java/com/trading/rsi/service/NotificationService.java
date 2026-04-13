package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.model.StochasticResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {
    
    private final ClaudeEnrichmentService claudeEnrichmentService;
    private final AppSettingsService appSettingsService;
    private final TelegramNotificationService telegramNotificationService;

    private final AtomicBoolean noTradeModeActive = new AtomicBoolean(false);
    private final Set<String> mutedSymbols = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void loadPersistedSettings() {
        boolean savedNoTradeMode = appSettingsService.getBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false);
        noTradeModeActive.set(savedNoTradeMode);
        Set<String> savedMuted = appSettingsService.getStringSet(AppSettingsService.KEY_MUTED_SYMBOLS);
        mutedSymbols.addAll(savedMuted);
        log.info("Settings loaded from DB — noTradeMode={} mutedSymbols={}", savedNoTradeMode, savedMuted);
    }
    
    @Value("${rsi.quiet-hours.enabled:true}")
    private boolean quietHoursEnabled;
    
    @Value("${rsi.quiet-hours.start-hour:22}")
    private int quietHoursStart;
    
    @Value("${rsi.quiet-hours.end-hour:8}")
    private int quietHoursEnd;

    @Value("${rsi.quiet-hours.suppress-partials-on-weekends:true}")
    private boolean suppressPartialsOnWeekends;

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
        
        RsiSignal signal = event.getSignal();
        boolean isFullSignal = signal.getTimeframesAligned() >= signal.getTotalTimeframes();
        if (mutedSymbols.contains(signal.getSymbol())) {
            log.debug("Symbol {} is muted, suppressing all signals", signal.getSymbol());
            return;
        }
        if (!isFullSignal && noTradeModeActive.get()) {
            log.debug("No-trade mode active, suppressing partial/watch signal for {}", signal.getSymbol());
            return;
        }
        if (!isFullSignal && isQuietHours()) {
            log.debug("Quiet hours active, suppressing partial signal for {}", signal.getSymbol());
            return;
        }
        if (!isFullSignal && suppressPartialsOnWeekends && isWeekend()) {
            log.debug("Weekend suppression active, skipping partial/watch signal for {}", signal.getSymbol());
            return;
        }
        if (isFullSignal && isQuietHours()) {
            log.info("Quiet hours but FULL signal — sending for {}", signal.getSymbol());
        }
        if (isFullSignal && isWeekend()) {
            log.info("Weekend but FULL signal — sending for {}", signal.getSymbol());
        }
        
        String aiContext = claudeEnrichmentService.enrichSignal(signal);
        sendNotification(signal, aiContext);
    }
    
    public void enableNoTradeMode() {
        noTradeModeActive.set(true);
        appSettingsService.setBoolean(AppSettingsService.KEY_NO_TRADE_MODE, true);
        log.info("No-trade mode ENABLED — PARTIAL and WATCH signals suppressed");
    }

    public void disableNoTradeMode() {
        noTradeModeActive.set(false);
        appSettingsService.setBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false);
        log.info("No-trade mode DISABLED — all signals active");
    }

    public boolean isNoTradeModeActive() {
        return noTradeModeActive.get();
    }

    public void muteSymbol(String symbol) {
        mutedSymbols.add(symbol.toUpperCase());
        appSettingsService.setStringSet(AppSettingsService.KEY_MUTED_SYMBOLS, mutedSymbols);
        log.info("Symbol {} muted — all alerts suppressed", symbol);
    }

    public void unmuteSymbol(String symbol) {
        mutedSymbols.remove(symbol.toUpperCase());
        appSettingsService.setStringSet(AppSettingsService.KEY_MUTED_SYMBOLS, mutedSymbols);
        log.info("Symbol {} unmuted — alerts active", symbol);
    }

    public Set<String> getMutedSymbols() {
        return Set.copyOf(mutedSymbols);
    }

    private void sendNotification(RsiSignal signal, String aiContext) {
        String title = buildNotificationTitle(signal);
        String message = buildNotificationMessage(signal, aiContext);
        telegramNotificationService.send(title, message);
    }
    
    private String buildNotificationTitle(RsiSignal signal) {
        String emoji = switch (signal.getSignalType()) {
            case OVERSOLD -> "🟢";
            case OVERBOUGHT -> "🔴";
            case PARTIAL_OVERSOLD -> "🟡";
            case PARTIAL_OVERBOUGHT -> "🟠";
            case WATCH_OVERSOLD -> "👀";
            case WATCH_OVERBOUGHT -> "👀";
        };
        
        String signalName = switch (signal.getSignalType()) {
            case OVERSOLD -> "BUY SIGNAL";
            case OVERBOUGHT -> "SELL SIGNAL";
            case PARTIAL_OVERSOLD -> "Partial Buy";
            case PARTIAL_OVERBOUGHT -> "Partial Sell";
            case WATCH_OVERSOLD -> "WATCH Buy";
            case WATCH_OVERBOUGHT -> "WATCH Sell";
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
        
        if (signal.getStochasticValues() != null && !signal.getStochasticValues().isEmpty()) {
            message.append("\nStochastic (14,3):\n");
            signal.getStochasticValues().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        StochasticResult s = entry.getValue();
                        message.append("  ").append(entry.getKey())
                               .append(": %K=").append(s.k())
                               .append(" %D=").append(s.d())
                               .append(" → ").append(s.label())
                               .append("\n");
                    });
        }

        message.append("\nAction: ");
        message.append(switch (signal.getSignalType()) {
            case OVERSOLD -> "Consider LONG position";
            case OVERBOUGHT -> "Consider SHORT/EXIT position";
            case PARTIAL_OVERSOLD -> "Watch for full alignment - potential LONG setup";
            case PARTIAL_OVERBOUGHT -> "Watch for full alignment - potential SHORT/EXIT";
            case WATCH_OVERSOLD -> "1 TF oversold + others approaching — check chart for entry";
            case WATCH_OVERBOUGHT -> "1 TF overbought + others approaching — check chart for exit";
        });

        boolean isPartial = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERBOUGHT;
        if (isPartial) {
            boolean oversoldSignal = signal.getSignalType() == SignalLog.SignalType.PARTIAL_OVERSOLD;
            double threshold = oversoldSignal ? 30.0 : 70.0;
            message.append("\n⏳ Waiting on:");
            signal.getRsiValues().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        double rsi = entry.getValue().doubleValue();
                        boolean aligned = oversoldSignal ? rsi < threshold : rsi > threshold;
                        if (!aligned) {
                            double gap = oversoldSignal ? rsi - threshold : threshold - rsi;
                            message.append(String.format(" %s RSI %.1f → needs %s%.0f (%.1f pts away)",
                                    entry.getKey(), rsi, oversoldSignal ? "<" : ">",
                                    threshold, gap));
                        }
                    });
        }

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
        sb.append("Stop: ").append(stopPts).append(" pts away (").append(stopPct)
          .append(isLong ? "% below entry" : "% above entry").append(")\n");
        sb.append("Limit: ").append(limitPts).append(" pts away (2:1 — profit ").append(accountCurrency)
          .append(" ").append(riskAmount.multiply(java.math.BigDecimal.valueOf(2)).toPlainString()).append(")\n");
        if (isCrypto) {
            sb.append("[Binance price — adjust pts if IG entry differs: stop = IG entry × ").append(stopPct).append("%]\n");
        }
        if (isPartial) {
            sb.append("⏳ Wait for ").append(signal.getTotalTimeframes()).append("/")
              .append(signal.getTotalTimeframes()).append(" TF alignment before entering");
        } else {
            sb.append("✅ Confirm chart, then place on IG demo");
        }
        return sb.toString();
    }

    private double inferStopPercent(String symbol) {
        if (symbol.startsWith("IX.")) return stopPercentIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return stopPercentCommodity;
        return stopPercentCrypto;
    }
    
    public void sendRawNotification(String title, String message, String priority, String tags) {
        telegramNotificationService.send(title, message);
    }

    private String getTagsForSignal(RsiSignal signal) {
        return switch (signal.getSignalType()) {
            case OVERSOLD -> "chart_with_upwards_trend,money_with_wings";
            case OVERBOUGHT -> "chart_with_downwards_trend,warning";
            case PARTIAL_OVERSOLD -> "eyes,chart_increasing";
            case PARTIAL_OVERBOUGHT -> "eyes,chart_decreasing";
            case WATCH_OVERSOLD -> "mag,chart_increasing";
            case WATCH_OVERBOUGHT -> "mag,chart_decreasing";
        };
    }
    
    private boolean isQuietHours() {
        if (!quietHoursEnabled) {
            return false;
        }
        
        int currentHour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        
        return quietHoursStart > quietHoursEnd
                ? currentHour >= quietHoursStart || currentHour < quietHoursEnd
                : currentHour >= quietHoursStart && currentHour < quietHoursEnd;
    }

    private boolean isWeekend() {
        DayOfWeek day = ZonedDateTime.now(ZoneOffset.UTC).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
