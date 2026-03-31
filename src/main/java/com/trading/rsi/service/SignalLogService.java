package com.trading.rsi.service;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalLogService {
    
    private final SignalLogRepository signalLogRepository;
    
    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        RsiSignal signal = event.getSignal();
        
        SignalLog logEntry = SignalLog.builder()
                .symbol(signal.getSymbol())
                .instrumentName(signal.getInstrumentName())
                .signalType(signal.getSignalType())
                .currentPrice(signal.getCurrentPrice())
                .rsi1m(signal.getRsiValues().get("1m"))
                .rsi5m(signal.getRsiValues().get("5m"))
                .rsi15m(signal.getRsiValues().get("15m"))
                .rsi1h(signal.getRsiValues().get("1h"))
                .rsi4h(signal.getRsiValues().get("4h"))
                .timeframesAligned(signal.getTimeframesAligned())
                .signalStrength(signal.getSignalStrength())
                .build();
        
        signalLogRepository.save(logEntry);
        log.info("Signal logged: {} {} at ${}", signal.getSymbol(), signal.getSignalType(), signal.getCurrentPrice());
    }
}
