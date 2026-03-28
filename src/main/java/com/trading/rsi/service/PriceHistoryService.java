package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PriceHistoryService {
    
    private static final int MAX_HISTORY_SIZE = 50;
    
    private final Map<String, LinkedList<BigDecimal>> priceHistory = new ConcurrentHashMap<>();
    
    public void updatePriceHistory(String key, Candle candle) {
        priceHistory.computeIfAbsent(key, k -> new LinkedList<>());
        LinkedList<BigDecimal> history = priceHistory.get(key);
        
        history.addLast(candle.getClose());
        
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }
    
    public void updatePriceHistory(String key, BigDecimal closePrice) {
        priceHistory.computeIfAbsent(key, k -> new LinkedList<>());
        LinkedList<BigDecimal> history = priceHistory.get(key);
        
        history.addLast(closePrice);
        
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }
    
    public LinkedList<BigDecimal> getPriceHistory(String key) {
        return priceHistory.getOrDefault(key, new LinkedList<>());
    }
    
    public boolean hasMinimumHistory(String key, int requiredSize) {
        LinkedList<BigDecimal> history = priceHistory.get(key);
        return history != null && history.size() >= requiredSize;
    }
    
    public void clearHistory(String key) {
        priceHistory.remove(key);
    }
    
    public String buildKey(String symbol, String timeframe) {
        return symbol + ":" + timeframe;
    }
}
