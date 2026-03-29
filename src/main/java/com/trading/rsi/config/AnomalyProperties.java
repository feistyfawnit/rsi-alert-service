package com.trading.rsi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {

    private boolean enabled = true;
    private VolumeSpikeConfig volumeSpike = new VolumeSpikeConfig();
    private PolymarketConfig polymarket = new PolymarketConfig();

    @Data
    public static class VolumeSpikeConfig {
        private boolean enabled = true;
        private double stdDevThreshold = 4.0;
        private int lookbackPeriods = 20;
        private int minPeriodsBeforeAlert = 10;
        private int cooldownMinutes = 30;
    }

    @Data
    public static class PolymarketConfig {
        private boolean enabled = true;
        private int pollIntervalSeconds = 300;
        private double oddsShiftThresholdPercent = 8.0;
        private int windowMinutes = 30;
        private List<MarketConfig> markets = new ArrayList<>();
    }

    @Data
    public static class MarketConfig {
        private String slug;
        private String name;
    }
}
