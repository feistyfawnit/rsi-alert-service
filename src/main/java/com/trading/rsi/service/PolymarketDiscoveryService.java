package com.trading.rsi.service;

import com.trading.rsi.config.AnomalyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers active prediction markets from Polymarket API.
 * Filters by liquidity, volume, and tag relevance to avoid expired/noisy markets.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketDiscoveryService {

    private final WebClient.Builder webClientBuilder;

    private static final String POLYMARKET_API = "https://gamma-api.polymarket.com";
    private static final String POLYMARKET_CLOB = "https://clob.polymarket.com";

    // Cache discovered markets for 1 hour to reduce API calls
    private final Map<String, DiscoveredMarket> marketCache = new ConcurrentHashMap<>();
    private Instant lastDiscovery = Instant.MIN;
    private static final long CACHE_MINUTES = 60;

    public record DiscoveredMarket(String slug, String name, String description,
                                    double volume24h, double liquidity, List<String> tags) {
        public boolean isActive() {
            return volume24h > 10000 && liquidity > 5000; // Minimum $10k volume, $5k liquidity
        }
    }

    /**
     * Fetch active markets matching configured discovery tags.
     * Returns cached results if within CACHE_MINUTES.
     */
    public List<DiscoveredMarket> discoverMarkets(AnomalyProperties.PolymarketConfig config) {
        if (!shouldRefreshCache()) {
            List<DiscoveredMarket> cached = new ArrayList<>(marketCache.values());
            log.debug("Using cached Polymarket markets ({} markets, cache age: {} min)",
                cached.size(), java.time.Duration.between(lastDiscovery, Instant.now()).toMinutes());
            return cached;
        }

        List<String> tags = config.getDiscoveryTags();
        if (tags == null || tags.isEmpty()) {
            return List.of(); // Discovery disabled, use manual markets
        }

        Set<String> excluded = config.getExcludedSlugs() != null
            ? Set.copyOf(config.getExcludedSlugs())
            : Set.of();

        try {
            // Primary discovery: /markets endpoint with tag filter
            List<DiscoveredMarket> markets = fetchMarketsByTags(tags, excluded, config.getMaxDiscoveryMarkets());

            if (markets.isEmpty()) {
                log.warn("Polymarket discovery returned no active markets for tags: {}", tags);
            } else {
                log.info("Discovered {} active Polymarket markets for tags {}", markets.size(), tags);
                // Update cache
                marketCache.clear();
                markets.forEach(m -> marketCache.put(m.slug(), m));
                lastDiscovery = Instant.now();
            }

            return markets;
        } catch (Exception e) {
            log.error("Polymarket discovery failed: {}. Using cache if available.", e.getMessage());
            return new ArrayList<>(marketCache.values());
        }
    }

    private List<DiscoveredMarket> fetchMarketsByTags(List<String> tags, Set<String> excluded, int limit) {
        WebClient client = webClientBuilder.baseUrl(POLYMARKET_API).build();

        // Fetch active markets with liquidity filter
        // API: /markets?active=true&liquidityMin=5000&volumeMin=10000&sort=volume&order=desc
        return client.get()
            .uri(uriBuilder -> uriBuilder
                .path("/markets")
                .queryParam("active", "true")
                .queryParam("liquidityMin", "5000")
                .queryParam("volumeMin", "10000")
                .queryParam("sort", "volume")
                .queryParam("order", "desc")
                .queryParam("limit", Math.min(limit * 2, 50)) // Fetch extra for filtering
                .build())
            .retrieve()
            .bodyToFlux(Map.class)
            .filter(market -> {
                // Tag matching: market must have at least one requested tag
                Object tagsObj = market.get("tags");
                if (tagsObj instanceof List<?> marketTags) {
                    return marketTags.stream()
                        .map(Object::toString)
                        .map(String::toLowerCase)
                        .anyMatch(tag -> tags.stream().map(String::toLowerCase).anyMatch(tag::contains));
                }
                return false;
            })
            .map(this::parseMarket)
            .filter(m -> !excluded.contains(m.slug()))
            .take(limit)
            .collectList()
            .block(java.time.Duration.ofSeconds(10));
    }

    private DiscoveredMarket parseMarket(Map<String, Object> market) {
        String slug = safeString(market.get("slug"));
        String name = safeString(market.get("question")); // Polymarket uses "question" for market name
        String description = safeString(market.get("description"));

        double volume24h = safeDouble(market.get("volume24h"));
        double liquidity = safeDouble(market.get("liquidity"));

        Object tagsObj = market.get("tags");
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> tagList) {
            tags = tagList.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        }

        return new DiscoveredMarket(slug, name, description, volume24h, liquidity, tags);
    }

    private String safeString(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private double safeDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private boolean shouldRefreshCache() {
        return java.time.Duration.between(lastDiscovery, Instant.now()).toMinutes() >= CACHE_MINUTES
            || marketCache.isEmpty();
    }

    /**
     * Clear cache and force fresh discovery on next poll.
     */
    public void invalidateCache() {
        marketCache.clear();
        lastDiscovery = Instant.MIN;
        log.info("Polymarket market cache invalidated");
    }
}
