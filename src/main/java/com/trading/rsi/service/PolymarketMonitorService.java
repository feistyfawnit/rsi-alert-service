package com.trading.rsi.service;

import com.trading.rsi.config.AnomalyProperties;
import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketMonitorService {

    private final WebClient.Builder webClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final AnomalyProperties anomalyProperties;

    private static final String POLYMARKET_API = "https://gamma-api.polymarket.com";

    private final Map<String, Deque<OddsReading>> oddsHistory = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlertTime = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${anomaly.polymarket.poll-interval-seconds:300}000")
    public void pollPolymarketOdds() {
        AnomalyProperties.PolymarketConfig cfg = anomalyProperties.getPolymarket();
        if (!anomalyProperties.isEnabled() || !cfg.isEnabled() || cfg.getMarkets().isEmpty()) {
            return;
        }

        WebClient client = webClientBuilder.baseUrl(POLYMARKET_API).build();

        for (AnomalyProperties.MarketConfig market : cfg.getMarkets()) {
            try {
                fetchAndAnalyze(client, market.getSlug(), market.getName());
            } catch (Exception e) {
                log.debug("Polymarket poll failed for {}: {}", market.getSlug(), e.getMessage());
            }
        }
    }

    private void fetchAndAnalyze(WebClient client, String slug, String name) {
        client.get()
                .uri("/markets?slug={slug}", slug)
                .retrieve()
                .bodyToFlux(Map.class)
                .next()
                .subscribe(
                        market -> processMarketData(slug, name, market),
                        error -> log.warn("Failed to fetch Polymarket [{}]: {}", slug, error.getMessage())
                );
    }

    @SuppressWarnings("unchecked")
    private void processMarketData(String slug, String name, Map<String, Object> market) {
        Object outcomePricesRaw = market.get("outcomePrices");
        if (outcomePricesRaw == null) return;

        BigDecimal yesProbabilityPct;
        try {
            List<?> prices;
            if (outcomePricesRaw instanceof List<?> list) {
                prices = list;
            } else {
                String s = outcomePricesRaw.toString().replaceAll("[\\[\\]\"\\s]", "");
                prices = Arrays.asList(s.split(","));
            }
            if (prices.isEmpty()) return;
            yesProbabilityPct = new BigDecimal(prices.get(0).toString())
                    .multiply(BigDecimal.valueOf(100));
        } catch (Exception e) {
            log.debug("Could not parse outcomePrices for Polymarket [{}]", slug);
            return;
        }

        Instant now = Instant.now();
        AnomalyProperties.PolymarketConfig cfg = anomalyProperties.getPolymarket();
        int windowMinutes = cfg.getWindowMinutes();

        Deque<OddsReading> history = oddsHistory.computeIfAbsent(slug, k -> new ArrayDeque<>());

        BigDecimal min, max, firstProb;
        synchronized (history) {
            history.removeIf(r -> r.timestamp().isBefore(now.minus(windowMinutes, ChronoUnit.MINUTES)));
            history.addLast(new OddsReading(now, yesProbabilityPct));
            if (history.size() < 2) return;
            firstProb = history.peekFirst().probability();
            min = history.stream().map(OddsReading::probability).min(BigDecimal::compareTo).orElse(yesProbabilityPct);
            max = history.stream().map(OddsReading::probability).max(BigDecimal::compareTo).orElse(yesProbabilityPct);
        }
        double shiftPp = max.subtract(min).doubleValue();
        boolean oddsRising = yesProbabilityPct.compareTo(firstProb) > 0;
        String directionArrow = oddsRising ? "▲" : "▼";

        log.debug("Polymarket [{}] YES={}% | {}min shift={}pp", name,
                String.format("%.1f", yesProbabilityPct.doubleValue()), windowMinutes,
                String.format("%.1f", shiftPp));

        double threshold = cfg.getOddsShiftThresholdPercent();
        if (shiftPp >= threshold) {
            Instant cooldownBoundary = now.minus(windowMinutes, ChronoUnit.MINUTES);
            boolean[] claimed = {false};
            lastAlertTime.compute(slug, (k, prev) -> {
                if (prev == null || prev.isBefore(cooldownBoundary)) {
                    claimed[0] = true;
                    return now;
                }
                return prev;
            });
            if (claimed[0]) {
                AnomalyAlert.Severity severity = shiftPp >= threshold * 2
                        ? AnomalyAlert.Severity.CRITICAL : AnomalyAlert.Severity.HIGH;

                AnomalyAlert alert = AnomalyAlert.builder()
                        .type(AnomalyAlert.AnomalyType.POLYMARKET_ODDS_SHIFT)
                        .severity(severity)
                        .market(name)
                        .description(String.format("Prediction market: %.1fpp %s shift in %dmin — %s",
                                shiftPp, directionArrow, windowMinutes, name))
                        .detectedAt(now)
                        .details(Map.of(
                                "slug", slug,
                                "yesProbabilityPct", yesProbabilityPct.doubleValue(),
                                "shiftPp", shiftPp,
                                "windowMinutes", windowMinutes
                        ))
                        .build();

                log.warn("POLYMARKET ANOMALY: {} — {}pp shift in {}min (YES={}%)",
                        name, String.format("%.1f", shiftPp), windowMinutes,
                        String.format("%.1f", yesProbabilityPct.doubleValue()));
                eventPublisher.publishEvent(new AnomalyEvent(this, alert));
            }
        }
    }

    private record OddsReading(Instant timestamp, BigDecimal probability) {}
}
