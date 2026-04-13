package com.trading.rsi.service;

import com.trading.rsi.domain.AppSetting;
import com.trading.rsi.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    @Mock
    private AppSettingRepository repository;

    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingsService(repository);
    }

    @Test
    void get_withExistingValue_returnsValue() {
        AppSetting setting = AppSetting.builder()
            .key("test_key")
            .value("test_value")
            .build();
        
        when(repository.findById("test_key")).thenReturn(Optional.of(setting));
        
        String result = service.get("test_key", "default");
        
        assertEquals("test_value", result);
    }

    @Test
    void get_withNonExistingKey_returnsDefault() {
        when(repository.findById("unknown_key")).thenReturn(Optional.empty());
        
        String result = service.get("unknown_key", "default_value");
        
        assertEquals("default_value", result);
    }

    @Test
    void get_withNullValue_returnsDefault() {
        AppSetting setting = AppSetting.builder()
            .key("test_key")
            .value(null)
            .build();
        
        when(repository.findById("test_key")).thenReturn(Optional.of(setting));
        
        String result = service.get("test_key", "default");
        
        assertEquals("default", result);
    }

    @Test
    void getBoolean_withTrueValue_returnsTrue() {
        AppSetting setting = AppSetting.builder()
            .key("enabled")
            .value("true")
            .build();
        
        when(repository.findById("enabled")).thenReturn(Optional.of(setting));
        
        assertTrue(service.getBoolean("enabled", false));
    }

    @Test
    void getBoolean_withFalseValue_returnsFalse() {
        AppSetting setting = AppSetting.builder()
            .key("enabled")
            .value("false")
            .build();
        
        when(repository.findById("enabled")).thenReturn(Optional.of(setting));
        
        assertFalse(service.getBoolean("enabled", true));
    }

    @Test
    void getBoolean_withNonExistingKey_returnsDefault() {
        when(repository.findById("enabled")).thenReturn(Optional.empty());
        
        assertTrue(service.getBoolean("enabled", true));
        assertFalse(service.getBoolean("enabled", false));
    }

    @Test
    void getBoolean_withInvalidValue_returnsDefault() {
        AppSetting setting = AppSetting.builder()
            .key("enabled")
            .value("not_a_boolean")
            .build();
        
        when(repository.findById("enabled")).thenReturn(Optional.of(setting));
        
        // Boolean.parseBoolean returns false for invalid values
        assertFalse(service.getBoolean("enabled", true));
    }

    @Test
    void getStringSet_withCommaSeparatedValues_returnsSet() {
        AppSetting setting = AppSetting.builder()
            .key("symbols")
            .value("BTC,ETH,SOL")
            .build();
        
        when(repository.findById("symbols")).thenReturn(Optional.of(setting));
        
        Set<String> result = service.getStringSet("symbols");
        
        assertEquals(Set.of("BTC", "ETH", "SOL"), result);
    }

    @Test
    void getStringSet_withEmptyValue_returnsEmptySet() {
        when(repository.findById("symbols")).thenReturn(Optional.empty());
        
        Set<String> result = service.getStringSet("symbols");
        
        assertEquals(Collections.emptySet(), result);
    }

    @Test
    void getStringSet_withBlankValue_returnsEmptySet() {
        AppSetting setting = AppSetting.builder()
            .key("symbols")
            .value("  ")
            .build();
        
        when(repository.findById("symbols")).thenReturn(Optional.of(setting));
        
        Set<String> result = service.getStringSet("symbols");
        
        assertTrue(result.isEmpty());
    }

    @Test
    void getStringSet_trimsWhitespace() {
        AppSetting setting = AppSetting.builder()
            .key("symbols")
            .value("  BTC  ,  ETH  ,  SOL  ")
            .build();
        
        when(repository.findById("symbols")).thenReturn(Optional.of(setting));
        
        Set<String> result = service.getStringSet("symbols");
        
        assertEquals(Set.of("BTC", "ETH", "SOL"), result);
    }

    @Test
    void getStringSet_withSingleValue_returnsSingletonSet() {
        AppSetting setting = AppSetting.builder()
            .key("symbol")
            .value("BTC")
            .build();
        
        when(repository.findById("symbol")).thenReturn(Optional.of(setting));
        
        Set<String> result = service.getStringSet("symbol");
        
        assertEquals(Set.of("BTC"), result);
    }

    @Test
    void set_createsNewSetting() {
        when(repository.findById("new_key")).thenReturn(Optional.empty());
        when(repository.save(any(AppSetting.class))).thenAnswer(i -> i.getArgument(0));
        
        service.set("new_key", "new_value");
        
        verify(repository).save(argThat(setting -> 
            setting.getKey().equals("new_key") && 
            setting.getValue().equals("new_value")
        ));
    }

    @Test
    void set_updatesExistingSetting() {
        AppSetting existing = AppSetting.builder()
            .key("existing_key")
            .value("old_value")
            .build();
        
        when(repository.findById("existing_key")).thenReturn(Optional.of(existing));
        when(repository.save(any(AppSetting.class))).thenAnswer(i -> i.getArgument(0));
        
        service.set("existing_key", "new_value");
        
        verify(repository).save(argThat(setting -> 
            setting.getKey().equals("existing_key") && 
            setting.getValue().equals("new_value")
        ));
    }

    @Test
    void setBoolean_savesAsString() {
        when(repository.findById("flag")).thenReturn(Optional.empty());
        when(repository.save(any(AppSetting.class))).thenAnswer(i -> i.getArgument(0));
        
        service.setBoolean("flag", true);
        
        verify(repository).save(argThat(setting -> 
            setting.getValue().equals("true")
        ));
    }

    @Test
    void setStringSet_savesAsCommaSeparated() {
        when(repository.findById("symbols")).thenReturn(Optional.empty());
        when(repository.save(any(AppSetting.class))).thenAnswer(i -> i.getArgument(0));
        
        service.setStringSet("symbols", Set.of("BTC", "ETH", "SOL"));
        
        verify(repository).save(argThat(setting -> 
            setting.getValue().contains("BTC") &&
            setting.getValue().contains("ETH") &&
            setting.getValue().contains("SOL")
        ));
    }

    // Test the static key constants
    @Test
    void keyConstants_areDefined() {
        assertEquals("no_trade_mode", AppSettingsService.KEY_NO_TRADE_MODE);
        assertEquals("muted_symbols", AppSettingsService.KEY_MUTED_SYMBOLS);
        assertEquals("active_position", AppSettingsService.KEY_ACTIVE_POSITION);
    }
}
