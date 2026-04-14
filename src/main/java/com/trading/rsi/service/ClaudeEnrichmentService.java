package com.trading.rsi.service;

import com.trading.rsi.model.RsiSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@Slf4j
public class ClaudeEnrichmentService {

    private final WebClient claudeClient;
    private final String apiKey;
    private final String model;
    private final boolean enabled;

    public ClaudeEnrichmentService(
            @Value("${claude.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${claude.api-key:}") String apiKey,
            @Value("${claude.model:claude-3-haiku-20240307}") String model,
            @Value("${claude.enabled:false}") boolean enabled,
            WebClient.Builder builder) {
        this.claudeClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }

    public String enrichSignal(RsiSignal signal) {
        if (!isEnabled()) {
            return null;
        }
        try {
            String prompt = buildPrompt(signal);
            ClaudeRequest request = new ClaudeRequest(model, 200,
                    List.of(new ClaudeMessage("user", prompt)));

            ClaudeResponse response = claudeClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ClaudeResponse.class)
                    .block();

            if (response != null && response.getContent() != null && !response.getContent().isEmpty()) {
                return response.getContent().get(0).getText();
            }
        } catch (Exception e) {
            log.warn("Claude enrichment failed for {} — sending signal without AI context: {}",
                    signal.getSymbol(), e.getMessage());
        }
        return null;
    }

    private String buildPrompt(RsiSignal signal) {
        String direction = switch (signal.getSignalType()) {
            case OVERSOLD, PARTIAL_OVERSOLD, WATCH_OVERSOLD -> "oversold (potential buy)";
            case OVERBOUGHT, PARTIAL_OVERBOUGHT, WATCH_OVERBOUGHT -> "overbought (potential sell/exit)";
            case TREND_BUY_DIP -> "trend buy-the-dip (strong uptrend, RSI pullback)";
            case TREND_SELL_RALLY -> "trend sell-the-rally (strong downtrend, RSI bounce)";
        };
        return String.format(
                "RSI alert: %s (%s) is showing %s signal. " +
                "RSI values: %s. Current price: %s. " +
                "In 2-3 sentences: summarise any recent news or market context relevant to this instrument " +
                "and give a brief signal confidence note (HIGH/MEDIUM/LOW). Be concise and factual.",
                signal.getInstrumentName(), signal.getSymbol(),
                direction, signal.getRsiValues(), signal.getCurrentPrice());
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ClaudeRequest {
        private String model;
        private int max_tokens;
        private List<ClaudeMessage> messages;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ClaudeMessage {
        private String role;
        private String content;
    }

    @lombok.Data
    private static class ClaudeResponse {
        private List<ContentBlock> content;

        @lombok.Data
        static class ContentBlock {
            private String type;
            private String text;
        }
    }
}
