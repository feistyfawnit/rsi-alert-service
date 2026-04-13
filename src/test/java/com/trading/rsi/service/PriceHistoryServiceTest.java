package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.CandleHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceHistoryServiceTest {

    @Mock
    private CandleHistoryRepository candleHistoryRepository;

    @InjectMocks
    private PriceHistoryService priceHistoryService;

    @Test
    void updatePriceHistory_addsCandleToMemory() {
        Candle candle = createCandle(100.0);
        String key = "BTCUSDT:1h";

        priceHistoryService.updatePriceHistory(key, candle);

        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        assertFalse(history.isEmpty());
        assertEquals(0, history.get(0).compareTo(BigDecimal.valueOf(100.0)));
    }

    @Test
    void updatePriceHistory_trimsOldEntries() {
        String key = "BTCUSDT:1h";
        
        // Add more than 100 candles
        for (int i = 0; i < 105; i++) {
            priceHistoryService.updatePriceHistory(key, createCandle(100.0 + i));
        }

        List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
        assertTrue(history.size() <= 100, "History should be trimmed to 100");
    }

    @Test
    void hasMinimumHistory_withEnoughData_returnsTrue() {
        String key = "BTCUSDT:1h";
        
        for (int i = 0; i < 30; i++) {
            priceHistoryService.updatePriceHistory(key, createCandle(100.0 + i));
        }

        assertTrue(priceHistoryService.hasMinimumHistory(key, 28));
    }

    @Test
    void hasMinimumHistory_withInsufficientData_returnsFalse() {
        String key = "BTCUSDT:1h";
        
        for (int i = 0; i < 10; i++) {
            priceHistoryService.updatePriceHistory(key, createCandle(100.0 + i));
        }

        assertFalse(priceHistoryService.hasMinimumHistory(key, 28));
    }

    @Test
    void getLatestCandle_returnsMostRecent() {
        String key = "BTCUSDT:1h";
        Candle first = createCandle(100.0);
        Candle second = createCandle(105.0);
        
        priceHistoryService.updatePriceHistory(key, first);
        priceHistoryService.updatePriceHistory(key, second);

        Candle latest = priceHistoryService.getLatestCandle(key);
        assertEquals(0, latest.getClose().compareTo(BigDecimal.valueOf(105.0)));
    }

    @Test
    void buildKey_formatsCorrectly() {
        String key = priceHistoryService.buildKey("BTCUSDT", "1h");
        assertEquals("BTCUSDT:1h", key);
    }

    @Test
    void getPriceHistory_noData_returnsEmptyList() {
        List<BigDecimal> history = priceHistoryService.getPriceHistory("NONEXISTENT:1h");
        assertTrue(history.isEmpty());
    }

    @Test
    void updatePriceHistory_persistsToRepository() {
        String key = "BTCUSDT:1h";
        Candle candle = createCandle(100.0);

        priceHistoryService.updatePriceHistory(key, candle);

        verify(candleHistoryRepository, atLeastOnce()).insertIgnore(
                any(String.class), any(String.class), any(Instant.class),
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class));
    }

    private Candle createCandle(double close) {
        return Candle.builder()
            .open(BigDecimal.valueOf(close - 1))
            .high(BigDecimal.valueOf(close + 1))
            .low(BigDecimal.valueOf(close - 2))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(1000))
            .timestamp(Instant.now())
            .build();
    }
}
