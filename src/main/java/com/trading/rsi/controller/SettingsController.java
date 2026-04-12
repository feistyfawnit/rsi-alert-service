package com.trading.rsi.controller;

import com.trading.rsi.service.AppSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AppSettingsService appSettingsService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll() {
        String activePosition = appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "");
        boolean noTradeMode = appSettingsService.getBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false);
        return ResponseEntity.ok(Map.of(
                "noTradeMode", noTradeMode,
                "mutedSymbols", appSettingsService.getStringSet(AppSettingsService.KEY_MUTED_SYMBOLS),
                "activePosition", activePosition != null ? activePosition : ""
        ));
    }

    @GetMapping("/active-position")
    public ResponseEntity<Map<String, Object>> getActivePosition() {
        String pos = appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "");
        boolean hasPosition = pos != null && !pos.isBlank();
        return ResponseEntity.ok(Map.of(
                "activePosition", hasPosition ? pos : "",
                "hasOpenPosition", hasPosition
        ));
    }

    @PostMapping("/active-position/{symbol}")
    public ResponseEntity<Map<String, Object>> setActivePosition(@PathVariable String symbol) {
        appSettingsService.set(AppSettingsService.KEY_ACTIVE_POSITION, symbol.toUpperCase());
        return ResponseEntity.ok(Map.of(
                "activePosition", symbol.toUpperCase(),
                "message", "Active position set to " + symbol.toUpperCase()
        ));
    }

    @DeleteMapping("/active-position")
    public ResponseEntity<Map<String, Object>> clearActivePosition() {
        appSettingsService.delete(AppSettingsService.KEY_ACTIVE_POSITION);
        return ResponseEntity.ok(Map.of(
                "activePosition", "",
                "message", "Active position cleared — anomaly alerts back to informational mode"
        ));
    }
}
