package com.trading.rsi.controller;

import com.trading.rsi.service.IGTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {

    private final IGTradingService tradingService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "autoExecutionEnabled", tradingService.isAutoExecutionEnabled(),
                "killSwitchActive", tradingService.isKillSwitchActive()
        ));
    }

    @PostMapping("/kill-switch/activate")
    public ResponseEntity<Map<String, String>> activateKillSwitch() {
        tradingService.activateKillSwitch();
        return ResponseEntity.ok(Map.of("status", "KILL SWITCH ACTIVATED — all auto-trading stopped"));
    }

    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Map<String, String>> deactivateKillSwitch() {
        tradingService.deactivateKillSwitch();
        return ResponseEntity.ok(Map.of("status", "Kill switch deactivated"));
    }
}
