package com.trading.rsi.service;

import com.trading.rsi.domain.AppSetting;
import com.trading.rsi.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppSettingsService {

    static final String KEY_NO_TRADE_MODE   = "no_trade_mode";
    static final String KEY_MUTED_SYMBOLS   = "muted_symbols";
    static final String KEY_ACTIVE_POSITION = "active_position";

    private final AppSettingRepository repository;

    public String get(String key, String defaultValue) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key, null);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }

    public Set<String> getStringSet(String key) {
        String val = get(key, "");
        if (val == null || val.isBlank()) return Collections.emptySet();
        return Arrays.stream(val.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public void set(String key, String value) {
        AppSetting setting = repository.findById(key)
                .orElse(AppSetting.builder().key(key).build());
        setting.setValue(value);
        repository.save(setting);
        log.debug("App setting saved: {} = {}", key, value);
    }

    public void setBoolean(String key, boolean value) {
        set(key, String.valueOf(value));
    }

    public void setStringSet(String key, Set<String> values) {
        set(key, String.join(",", values));
    }

    public void delete(String key) {
        repository.deleteById(key);
        log.debug("App setting deleted: {}", key);
    }
}
