package com.trading.rsi.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class AnomalyAlert {

    public enum AnomalyType {
        VOLUME_SPIKE,            // ✅ implemented — VolumeAnomalyDetector
        POLYMARKET_ODDS_SHIFT,   // ✅ implemented — PolymarketMonitorService
        CROSS_CORRELATION        // ⏳ planned — cross-instrument detector not yet built
    }

    public enum Severity {
        HIGH,
        CRITICAL
    }

    private AnomalyType type;
    private Severity severity;
    private String market;
    private String description;
    private Instant detectedAt;
    private Map<String, Object> details;
}
