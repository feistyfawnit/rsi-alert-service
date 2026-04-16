package com.trading.rsi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final WebClient.Builder webClientBuilder;

    @Value("${notifications.telegram.enabled:false}")
    private boolean enabled;

    @Value("${notifications.telegram.bot-token:}")
    private String botToken;

    @Value("${notifications.telegram.chat-ids:}")
    private String chatIds;

    private static final int MAX_LENGTH = 4096;

    public boolean isEnabled() {
        return enabled && !botToken.isBlank() && !chatIds.isBlank();
    }

    public void send(String title, String body) {
        if (!isEnabled()) return;

        String text = title + "\n\n" + body;
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH - 3) + "...";
        }

        List<String> recipients = Arrays.stream(chatIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();

        WebClient client = webClientBuilder.baseUrl("https://api.telegram.org").build();
        for (String chatId : recipients) {
            Map<String, String> payload = Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML");
            client.post()
                    .uri("/bot" + botToken + "/sendMessage")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(r -> log.info("Telegram notification sent to {}: {}", chatId, title))
                    .doOnError(e -> log.error("Failed to send Telegram to {}: {}", chatId, e.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        }
    }

}
