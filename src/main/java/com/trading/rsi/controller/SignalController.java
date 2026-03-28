package com.trading.rsi.controller;

import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {
    
    private final SignalLogRepository signalLogRepository;
    
    @GetMapping
    public List<SignalLog> getAllSignals() {
        return signalLogRepository.findAll();
    }
    
    @GetMapping("/symbol/{symbol}")
    public List<SignalLog> getSignalsBySymbol(@PathVariable String symbol) {
        return signalLogRepository.findBySymbolOrderByCreatedAtDesc(symbol);
    }
    
    @GetMapping("/recent")
    public List<SignalLog> getRecentSignals(
            @RequestParam(defaultValue = "24") int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return signalLogRepository.findByCreatedAtAfter(since);
    }
}
