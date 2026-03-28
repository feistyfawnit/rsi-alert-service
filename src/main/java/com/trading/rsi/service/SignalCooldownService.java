package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SignalCooldownService {
    
    private static final int COOLDOWN_MINUTES = 60;
    
    private final Map<String, LocalDateTime> lastAlertTimes = new ConcurrentHashMap<>();
    
    public boolean shouldAlert(String symbol, SignalLog.SignalType signalType) {
        String key = buildKey(symbol, signalType);
        LocalDateTime lastAlert = lastAlertTimes.get(key);
        
        if (lastAlert == null) {
            return true;
        }
        
        LocalDateTime cooldownExpiry = lastAlert.plusMinutes(COOLDOWN_MINUTES);
        boolean shouldAlert = LocalDateTime.now().isAfter(cooldownExpiry);
        
        if (!shouldAlert) {
            log.debug("Signal for {} {} still in cooldown period", symbol, signalType);
        }
        
        return shouldAlert;
    }
    
    public void recordAlert(String symbol, SignalLog.SignalType signalType) {
        String key = buildKey(symbol, signalType);
        lastAlertTimes.put(key, LocalDateTime.now());
        log.debug("Recorded alert for {} {} at {}", symbol, signalType, LocalDateTime.now());
    }
    
    private String buildKey(String symbol, SignalLog.SignalType signalType) {
        return symbol + ":" + signalType;
    }
}
