package com.trading.rsi.controller;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.AnomalyAlert;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.service.IGAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final ApplicationEventPublisher eventPublisher;
    private final InstrumentRepository instrumentRepository;
    private final IGAuthService igAuthService;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> triggerTestNotification() {
        Map<String, BigDecimal> rsiValues = new LinkedHashMap<>();
        rsiValues.put("15m", BigDecimal.valueOf(24.3));
        rsiValues.put("1h", BigDecimal.valueOf(26.7));
        rsiValues.put("4h", BigDecimal.valueOf(22.1));

        RsiSignal signal = Objects.requireNonNull(RsiSignal.builder()
                .symbol("SOLUSDT")
                .instrumentName("Solana [TEST]")
                .signalType(SignalLog.SignalType.OVERSOLD)
                .currentPrice(BigDecimal.valueOf(142.50))
                .rsiValues(rsiValues)
                .timeframesAligned(3)
                .totalTimeframes(3)
                .signalStrength(BigDecimal.valueOf(7.2))
                .build());

        eventPublisher.publishEvent(new SignalEvent(this, signal));
        return ResponseEntity.ok(Map.of("status", "Test notification dispatched — check Telegram"));
    }

    @PostMapping("/anomaly")
    public ResponseEntity<Map<String, String>> triggerTestAnomaly(
            @RequestParam(defaultValue = "volume") String type) {
        AnomalyAlert alert;
        if ("polymarket".equalsIgnoreCase(type)) {
            alert = Objects.requireNonNull(AnomalyAlert.builder()
                    .type(AnomalyAlert.AnomalyType.POLYMARKET_ODDS_SHIFT)
                    .severity(AnomalyAlert.Severity.HIGH)
                    .market("US tariff announcement [TEST]")
                    .description("Prediction market: 12.5pp odds shift in 30min — US tariff announcement [TEST]")
                    .detectedAt(Instant.now())
                    .details(Map.of(
                            "slug", "will-the-us-impose-new-tariffs-in-the-next-30-days",
                            "yesProbabilityPct", 67.5,
                            "shiftPp", 12.5,
                            "windowMinutes", 30
                    ))
                    .build());
        } else {
            alert = Objects.requireNonNull(AnomalyAlert.builder()
                    .type(AnomalyAlert.AnomalyType.VOLUME_SPIKE)
                    .severity(AnomalyAlert.Severity.HIGH)
                    .market("Solana (15m) [TEST]")
                    .description("Volume spike 4.8\u03c3 above baseline — Solana 15m [TEST]")
                    .detectedAt(Instant.now())
                    .details(Map.of(
                            "symbol", "SOLUSDT",
                            "timeframe", "15m",
                            "currentVolume", 485000.0,
                            "baselineMean", 82000.0,
                            "stdDev", 84000.0,
                            "zScore", 4.8
                    ))
                    .build());
        }
        eventPublisher.publishEvent(new AnomalyEvent(this, alert));
        return ResponseEntity.ok(Map.of("status", "Anomaly test alert dispatched (type=" + type + ") — check Telegram"));
    }

    @PostMapping("/lower-thresholds")
    public ResponseEntity<Map<String, Object>> lowerThresholds(
            @RequestParam(defaultValue = "50") int oversold,
            @RequestParam(defaultValue = "50") int overbought) {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        instruments.forEach(i -> {
            i.setOversoldThreshold(oversold);
            i.setOverboughtThreshold(overbought);
        });
        instrumentRepository.saveAll(instruments);
        return ResponseEntity.ok(Map.of(
                "updated", instruments.size(),
                "oversold", oversold,
                "overbought", overbought,
                "warning", "REAL signal notifications will fire on next poll — they will NOT be labelled [TEST]. Use POST /api/test/notify for a labelled test instead.",
                "next", "POST /api/test/reset-thresholds to restore 30/70 after testing"
        ));
    }

    @PostMapping("/reset-thresholds")
    public ResponseEntity<Map<String, Object>> resetThresholds() {
        List<Instrument> instruments = instrumentRepository.findAll();
        instruments.forEach(i -> {
            i.setOversoldThreshold(30);
            i.setOverboughtThreshold(70);
        });
        instrumentRepository.saveAll(instruments);
        return ResponseEntity.ok(Map.of("status", "Thresholds reset to 30/70 for all instruments"));
    }

    @GetMapping("/ig/search")
    public ResponseEntity<?> searchIGEpic(@RequestParam String term) {
        if (!igAuthService.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "IG API not enabled",
                    "fix", "Set IG_ENABLED=true and IG credentials in .env"
            ));
        }
        IGAuthService.IGSession session = igAuthService.getSession();
        if (session == null) {
            return ResponseEntity.status(503).body(Map.of("error", "IG session unavailable — check credentials"));
        }
        try {
            Object result = igAuthService.getClient().get()
                    .uri("/markets?searchTerm={term}", term)
                    .header("X-IG-API-KEY", igAuthService.getApiKey())
                    .header("CST", session.getCst())
                    .header("X-SECURITY-TOKEN", session.getSecurityToken())
                    .header("Version", "1")
                    .retrieve()
                    .bodyToMono(Object.class)
                    .block();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
