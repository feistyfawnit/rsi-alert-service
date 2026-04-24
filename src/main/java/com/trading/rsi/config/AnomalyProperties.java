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
    private CorrelationConfig correlation = new CorrelationConfig();
    private PolymarketConfig polymarket = new PolymarketConfig();

    public enum NotifyScope {
        /** Alert for every symbol (legacy behaviour). */
        ALL,
        /** Alert only when an active position exists OR a signal fired recently on this symbol. */
        RELEVANT,
        /** Alert only when an active position exists on this symbol. */
        OPEN_ONLY
    }

    @Data
    public static class VolumeSpikeConfig {
        private boolean enabled = true;
        private double stdDevThreshold = 4.0;
        private int lookbackPeriods = 20;
        private int minPeriodsBeforeAlert = 10;
        private int cooldownMinutes = 30;
        private double minBaselineVolume = 50.0;
        /** Telegram notification gate — does NOT affect DB logging. */
        private NotifyScope notifyScope = NotifyScope.ALL;
        /** For `RELEVANT` scope: how far back to look for a signal on the same symbol. */
        private int notifyRecentSignalHours = 4;
    }

    /**
     * Correlation burst: when many symbols fire anomalies simultaneously it is almost
     * always a macro / news event. Collapse into one CROSS_CORRELATION alert instead of
     * spamming N individual alerts.
     */
    @Data
    public static class CorrelationConfig {
        private boolean enabled = true;
        private int minInstruments = 3;
        private int windowSeconds = 60;
    }

    @Data
    public static class PolymarketConfig {
        private boolean enabled = true;
        private int pollIntervalSeconds = 300;
        private double oddsShiftThresholdPercent = 8.0;
        private int windowMinutes = 30;

        /**
         * Discovery mode: fetch active markets matching these tags.
         * If empty, falls back to manual markets list.
         * Recommended tags: "politics", "crypto", "sports", "macro"
         */
        private List<String> discoveryTags = new ArrayList<>();

        /**
         * Max markets to monitor from discovery. Limits API load.
         */
        private int maxDiscoveryMarkets = 10;

        /**
         * Manual market list (legacy mode). Only used if discoveryTags is empty.
         * Note: Prediction markets expire — prefer discovery mode.
         */
        private List<MarketConfig> markets = new ArrayList<>();

        /**
         * Markets to exclude from discovery (e.g., known noise).
         */
        private List<String> excludedSlugs = new ArrayList<>();
    }

    @Data
    public static class MarketConfig {
        private String slug;
        private String name;
    }
}
