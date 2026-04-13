package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class SignalCooldownServiceTest {

    private SignalCooldownService createService(int cooldownMinutes) {
        SignalCooldownService service = new SignalCooldownService();
        ReflectionTestUtils.setField(service, "cooldownMinutes", cooldownMinutes);
        return service;
    }

    @Test
    void shouldAlert_noPreviousSignal_returnsTrue() {
        SignalCooldownService service = createService(60);
        
        assertTrue(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
    }

    @Test
    void shouldAlert_afterRecording_returnsFalse() {
        SignalCooldownService service = createService(60);
        
        service.recordAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD);
        
        assertFalse(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
    }

    @Test
    void differentSignalTypes_areIndependent() {
        SignalCooldownService service = createService(60);
        
        service.recordAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD);
        
        // OVERSOLD is on cooldown, but OVERBOUGHT is not
        assertFalse(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
        assertTrue(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERBOUGHT));
    }

    @Test
    void differentSymbols_areIndependent() {
        SignalCooldownService service = createService(60);
        
        service.recordAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD);
        
        // BTC is on cooldown, but ETH is not
        assertFalse(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
        assertTrue(service.shouldAlert("ETHUSDT", SignalLog.SignalType.OVERSOLD));
    }

    @Test
    void recordAlert_resetsCooldown() {
        SignalCooldownService service = createService(60);
        
        // Manually inject an old timestamp
        Map<String, LocalDateTime> lastTimes = new ConcurrentHashMap<>();
        lastTimes.put("BTCUSDT:OVERSOLD", LocalDateTime.now().minusMinutes(90));
        ReflectionTestUtils.setField(service, "lastAlertTimes", lastTimes);
        
        // Should alert (90 min ago > 60 min cooldown)
        assertTrue(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
        
        // Record new alert
        service.recordAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD);
        
        // Now should NOT alert (just recorded)
        assertFalse(service.shouldAlert("BTCUSDT", SignalLog.SignalType.OVERSOLD));
    }
}
