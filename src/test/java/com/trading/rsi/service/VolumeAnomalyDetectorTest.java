package com.trading.rsi.service;

import com.trading.rsi.config.AnomalyProperties;
import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VolumeAnomalyDetectorTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AnomalyProperties anomalyProperties;

    @InjectMocks
    private VolumeAnomalyDetector detector;

    private AnomalyProperties.VolumeSpikeConfig volumeConfig;

    @BeforeEach
    void setUp() {
        volumeConfig = new AnomalyProperties.VolumeSpikeConfig();
        volumeConfig.setEnabled(true);
        volumeConfig.setStdDevThreshold(2.0); // Lower threshold for testing
        volumeConfig.setLookbackPeriods(10);
        volumeConfig.setMinPeriodsBeforeAlert(5);
        volumeConfig.setCooldownMinutes(0); // No cooldown for testing
        volumeConfig.setMinBaselineVolume(100);

        when(anomalyProperties.isEnabled()).thenReturn(true);
        when(anomalyProperties.getVolumeSpike()).thenReturn(volumeConfig);
    }

    @Test
    void onNewCandle_withNormalVolume_noAlert() {
        // Feed 10 candles with consistent volume
        for (int i = 0; i < 10; i++) {
            Candle candle = createCandle(1000 + i, 1000);
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);
        }

        // No alert should be fired
        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_withSpikeVolume_firesAlert() {
        // Vary baseline volume so stdDev > 0 (required by detector)
        int[] baseline = {900, 1100, 950, 1050, 1000, 1020, 980};
        for (int v : baseline) {
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", createCandle(1000, v));
        }

        // Spike volume (10x normal) — well above threshold=2.0
        Candle spikeCandle = createCandle(1007, 50000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", spikeCandle);

        verify(eventPublisher, atLeastOnce()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_withNullVolume_skips() {
        Candle candle = Candle.builder()
            .open(BigDecimal.valueOf(100))
            .high(BigDecimal.valueOf(101))
            .low(BigDecimal.valueOf(99))
            .close(BigDecimal.valueOf(100))
            .volume(null)
            .timestamp(Instant.now())
            .build();

        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);

        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_withZeroVolume_skips() {
        Candle candle = createCandle(100, 0);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);

        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_bullishDirection_detected() {
        int[] baseline = {900, 1100, 950, 1050, 1000, 1020, 980};
        for (int v : baseline) {
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h",
                createCandle(100, v, 100, 101));
        }

        // Spike with bullish direction
        Candle spikeCandle = Candle.builder()
            .open(BigDecimal.valueOf(106))
            .high(BigDecimal.valueOf(115))
            .low(BigDecimal.valueOf(106))
            .close(BigDecimal.valueOf(114)) // bullish
            .volume(BigDecimal.valueOf(10000))
            .timestamp(Instant.now())
            .build();
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", spikeCandle);

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        
        AnomalyEvent event = captor.getValue();
        assertEquals("bullish", event.getAlert().getDetails().get("direction"));
    }

    @Test
    void onNewCandle_bearishDirection_detected() {
        int[] baseline = {900, 1100, 950, 1050, 1000, 1020, 980};
        for (int v : baseline) {
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h",
                createCandle(100, v, 101, 100));
        }

        // Spike with bearish direction
        Candle spikeCandle = Candle.builder()
            .open(BigDecimal.valueOf(106))
            .high(BigDecimal.valueOf(106))
            .low(BigDecimal.valueOf(96))
            .close(BigDecimal.valueOf(97)) // bearish
            .volume(BigDecimal.valueOf(10000))
            .timestamp(Instant.now())
            .build();
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", spikeCandle);

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        
        AnomalyEvent event = captor.getValue();
        assertEquals("bearish", event.getAlert().getDetails().get("direction"));
    }

    @Test
    void onNewCandle_belowMinPeriods_noAlert() {
        // Only 3 candles - below min periods
        for (int i = 0; i < 3; i++) {
            Candle candle = createCandle(100 + i, 1000);
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);
        }

        // Even with spike, shouldn't alert
        Candle spikeCandle = createCandle(103, 10000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", spikeCandle);

        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_anomalyDisabled_noDetection() {
        when(anomalyProperties.isEnabled()).thenReturn(false);

        Candle candle = createCandle(100, 10000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);

        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_volumeSpikeDisabled_noDetection() {
        volumeConfig.setEnabled(false);

        Candle candle = createCandle(100, 10000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", candle);

        verify(eventPublisher, never()).publishEvent(any(AnomalyEvent.class));
    }

    @Test
    void onNewCandle_cooldownRespected() {
        volumeConfig.setCooldownMinutes(60);

        // Varied baseline so stdDev > 0
        int[] baseline = {900, 1100, 950, 1050, 1000, 1020, 980};
        for (int v : baseline) {
            detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", createCandle(100, v));
        }
        Candle firstSpike = createCandle(200, 50000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", firstSpike);

        verify(eventPublisher, times(1)).publishEvent(any(AnomalyEvent.class));

        // Immediate second spike - suppressed by cooldown
        Candle secondSpike = createCandle(200, 50000);
        detector.onNewCandle("BTCUSDT", "Bitcoin", "1h", secondSpike);

        verify(eventPublisher, times(1)).publishEvent(any(AnomalyEvent.class));
    }

    // Helper methods

    private Candle createCandle(double close, double volume) {
        return createCandle(close, volume, close - 1, close + 1);
    }

    private Candle createCandle(double close, double volume, double open, double high) {
        return Candle.builder()
            .open(BigDecimal.valueOf(open))
            .high(BigDecimal.valueOf(high))
            .low(BigDecimal.valueOf(open - 2))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(volume))
            .timestamp(Instant.now())
            .build();
    }
}
