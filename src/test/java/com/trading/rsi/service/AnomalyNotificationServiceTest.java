package com.trading.rsi.service;

import com.trading.rsi.event.AnomalyEvent;
import com.trading.rsi.model.AnomalyAlert;
import com.trading.rsi.repository.AnomalyLogRepository;
import com.trading.rsi.repository.SignalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnomalyNotificationServiceTest {

    @Mock
    private AppSettingsService appSettingsService;

    @Mock
    private TelegramNotificationService telegramNotificationService;

    @Mock
    private SignalLogRepository signalLogRepository;

    @Mock
    private AnomalyLogRepository anomalyLogRepository;

    @InjectMocks
    private AnomalyNotificationService notificationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "quietHoursEnabled", false);
    }

    @Test
    void handleAnomalyEvent_noActivePosition_sendsAlertWithoutPositionWarning() {
        when(appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "")).thenReturn("");

        AnomalyAlert alert = createVolumeAlert();
        AnomalyEvent event = new AnomalyEvent(this, alert);

        notificationService.handleAnomalyEvent(event);

        verify(telegramNotificationService, atLeastOnce()).send(anyString(), argThat(msg ->
                !msg.contains("YOU HAVE AN OPEN POSITION")));
    }

    @Test
    void handleAnomalyEvent_withActivePosition_sendsAlert() {
        when(appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "")).thenReturn("BTCUSDT");

        AnomalyAlert alert = createVolumeAlert();
        AnomalyEvent event = new AnomalyEvent(this, alert);

        notificationService.handleAnomalyEvent(event);

        verify(telegramNotificationService, atLeastOnce()).send(anyString(), anyString());
    }

    @Test
    void handleAnomalyEvent_quietHoursWithHighSeverity_sendsAnyway() {
        ReflectionTestUtils.setField(notificationService, "quietHoursEnabled", true);
        ReflectionTestUtils.setField(notificationService, "quietHoursStart", 22);
        ReflectionTestUtils.setField(notificationService, "quietHoursEnd", 8);

        when(appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "")).thenReturn("BTCUSDT");

        AnomalyAlert alert = createCriticalVolumeAlert();
        AnomalyEvent event = new AnomalyEvent(this, alert);

        notificationService.handleAnomalyEvent(event);

        verify(telegramNotificationService, atLeastOnce()).send(anyString(), anyString());
    }

    private AnomalyAlert createVolumeAlert() {
        return AnomalyAlert.builder()
            .type(AnomalyAlert.AnomalyType.VOLUME_SPIKE)
            .severity(AnomalyAlert.Severity.HIGH)
            .market("BTCUSDT (1h)")
            .description("Volume spike detected")
            .detectedAt(Instant.now())
            .details(Map.of("symbol", "BTCUSDT", "zScore", 9.5))
            .build();
    }

    private AnomalyAlert createCriticalVolumeAlert() {
        return AnomalyAlert.builder()
            .type(AnomalyAlert.AnomalyType.VOLUME_SPIKE)
            .severity(AnomalyAlert.Severity.CRITICAL)
            .market("BTCUSDT (1h)")
            .description("Critical volume spike detected")
            .detectedAt(Instant.now())
            .details(Map.of("symbol", "BTCUSDT", "zScore", 15.0))
            .build();
    }
}
