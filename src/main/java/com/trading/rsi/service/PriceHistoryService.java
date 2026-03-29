package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PriceHistoryService {
    
    private static final int MAX_HISTORY_SIZE = 50;
    
    private final Map<String, LinkedList<BigDecimal>> priceHistory = new ConcurrentHashMap<>();
    
    public void updatePriceHistory(String key, Candle candle) {
        updatePriceHistory(key, candle.getClose());
    }
    
    public void updatePriceHistory(String key, BigDecimal closePrice) {
        LinkedList<BigDecimal> history = priceHistory.computeIfAbsent(key, k -> new LinkedList<>());
        synchronized (history) {
            history.addLast(closePrice);
            if (history.size() > MAX_HISTORY_SIZE) {
                history.removeFirst();
            }
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
    
    public void clearHistory(String key) {
        priceHistory.remove(key);
    }
    
    public String buildKey(String symbol, String timeframe) {
        return symbol + ":" + timeframe;
    }
}
