package com.trading.rsi.controller;

import com.trading.rsi.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppSettingsService appSettingsService;

    @BeforeEach
    void setUp() {
        when(appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "")).thenReturn("");
        when(appSettingsService.getBoolean(AppSettingsService.KEY_NO_TRADE_MODE, false)).thenReturn(false);
        when(appSettingsService.getStringSet(AppSettingsService.KEY_MUTED_SYMBOLS)).thenReturn(Collections.emptySet());
    }

    @Test
    void getAllSettings_returnsMap() throws Exception {
        mockMvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.noTradeMode").value(false))
            .andExpect(jsonPath("$.activePosition").value(""));
    }

    @Test
    void getActivePosition_returnsJsonWithFields() throws Exception {
        when(appSettingsService.get(AppSettingsService.KEY_ACTIVE_POSITION, "")).thenReturn("BTCUSDT");

        mockMvc.perform(get("/api/settings/active-position"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activePosition").value("BTCUSDT"))
            .andExpect(jsonPath("$.hasOpenPosition").value(true));
    }

    @Test
    void getActivePosition_noPosition_hasOpenPositionFalse() throws Exception {
        mockMvc.perform(get("/api/settings/active-position"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasOpenPosition").value(false));
    }

    @Test
    void setActivePosition_withSymbol_updatesViaPathVariable() throws Exception {
        mockMvc.perform(post("/api/settings/active-position/ETHUSDT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activePosition").value("ETHUSDT"));

        verify(appSettingsService).set(AppSettingsService.KEY_ACTIVE_POSITION, "ETHUSDT");
    }

    @Test
    void clearActivePosition_deletesKey() throws Exception {
        mockMvc.perform(delete("/api/settings/active-position"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activePosition").value(""));

        verify(appSettingsService).delete(AppSettingsService.KEY_ACTIVE_POSITION);
    }
}
