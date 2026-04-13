package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.model.Candle;
import com.trading.rsi.repository.CandleHistoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PriceHistoryService {

    private static final int MAX_HISTORY_SIZE = 100;
    private static final int DB_TRIM_THRESHOLD = 120;

    private final CandleHistoryRepository candleHistoryRepository;

    private final Map<String, LinkedList<BigDecimal>> priceHistory = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Candle>> candleHistory = new ConcurrentHashMap<>();
    private final Map<String, Candle> latestCandleByKey = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> latestPriceBySymbol = new ConcurrentHashMap<>();

    @PostConstruct
    void loadFromDatabase() {
        List<CandleHistory> all = candleHistoryRepository.findAll();
        if (all.isEmpty()) {
            log.info("No persisted candle history found — will warm up from API on first poll");
            return;
        }

        // Group by symbol:timeframe and load into memory
        Map<String, List<CandleHistory>> grouped = new HashMap<>();
        for (CandleHistory ch : all) {
            grouped.computeIfAbsent(ch.getSymbol() + ":" + ch.getTimeframe(), k -> new ArrayList<>()).add(ch);
        }

        int keysLoaded = 0;
        int candlesLoaded = 0;
        for (Map.Entry<String, List<CandleHistory>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            List<CandleHistory> rows = entry.getValue().stream()
                    .sorted((a, b) -> a.getCandleTime().compareTo(b.getCandleTime()))
                    .toList();
            for (CandleHistory row : rows) {
                Candle candle = Candle.builder()
                        .timestamp(row.getCandleTime())
                        .open(row.getOpen())
                        .high(row.getHigh())
                        .low(row.getLow())
                        .close(row.getClose())
                        .volume(row.getVolume())
                        .build();
                loadCandleIntoMemory(key, candle);
                candlesLoaded++;
            }
            keysLoaded++;
        }
        log.info("Candle history loaded from DB: {} symbol/timeframe pairs, {} candles total", keysLoaded, candlesLoaded);
    }

    private void loadCandleIntoMemory(String key, Candle candle) {
        latestCandleByKey.put(key, candle);
        LinkedList<Candle> candles = candleHistory.computeIfAbsent(key, k -> new LinkedList<>());
        candles.addLast(candle);
        while (candles.size() > MAX_HISTORY_SIZE) candles.removeFirst();
        LinkedList<BigDecimal> history = priceHistory.computeIfAbsent(key, k -> new LinkedList<>());
        history.addLast(candle.getClose());
        while (history.size() > MAX_HISTORY_SIZE) history.removeFirst();
        String symbol = key.contains(":") ? key.split(":")[0] : key;
        latestPriceBySymbol.put(symbol, candle.getClose());
    }

    public void updatePriceHistory(String key, Candle candle) {
        latestCandleByKey.put(key, candle);
        LinkedList<Candle> candles = candleHistory.computeIfAbsent(key, k -> new LinkedList<>());
        synchronized (candles) {
            candles.addLast(candle);
            if (candles.size() > MAX_HISTORY_SIZE) {
                candles.removeFirst();
            }
        }
        updatePriceHistory(key, candle.getClose());
        persistCandle(key, candle);
    }

    public void updatePriceHistory(String key, BigDecimal closePrice) {
        LinkedList<BigDecimal> history = priceHistory.computeIfAbsent(key, k -> new LinkedList<>());
        synchronized (history) {
            history.addLast(closePrice);
            if (history.size() > MAX_HISTORY_SIZE) {
                history.removeFirst();
            }
        }
        String symbol = key.contains(":") ? key.split(":")[0] : key;
        latestPriceBySymbol.put(symbol, closePrice);
    }

    private void persistCandle(String key, Candle candle) {
        if (candle.getTimestamp() == null) return;
        String[] parts = key.split(":", 2);
        if (parts.length != 2) return;
        String symbol = parts[0];
        String timeframe = parts[1];

        try {
            candleHistoryRepository.insertIgnore(symbol, timeframe, candle.getTimestamp(),
                    candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());

            long count = candleHistoryRepository.countBySymbolAndTimeframe(symbol, timeframe);
            if (count > DB_TRIM_THRESHOLD) {
                candleHistoryRepository.trimToLatest(symbol, timeframe, MAX_HISTORY_SIZE);
            }
        } catch (Exception e) {
            log.warn("Failed to persist candle for {}: {}", key, e.getMessage());
        }
    }
    
    public List<BigDecimal> getPriceHistory(String key) {
        LinkedList<BigDecimal> history = priceHistory.get(key);
        if (history == null) return List.of();
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }
    
    public boolean hasMinimumHistory(String key, int requiredSize) {
        LinkedList<BigDecimal> history = priceHistory.get(key);
        if (history == null) return false;
        synchronized (history) {
            return history.size() >= requiredSize;
        }
    }
    
    public List<Candle> getCandleHistory(String key) {
        LinkedList<Candle> candles = candleHistory.get(key);
        if (candles == null) return List.of();
        synchronized (candles) {
            return new ArrayList<>(candles);
        }
    }

    public Candle getLatestCandle(String key) {
        return latestCandleByKey.get(key);
    }

    public BigDecimal getLatestPrice(String symbol) {
        return latestPriceBySymbol.get(symbol);
    }

    public void clearHistory(String key) {
        priceHistory.remove(key);
        candleHistory.remove(key);
        latestCandleByKey.remove(key);
    }
    
    public String buildKey(String symbol, String timeframe) {
        return symbol + ":" + timeframe;
    }
}
