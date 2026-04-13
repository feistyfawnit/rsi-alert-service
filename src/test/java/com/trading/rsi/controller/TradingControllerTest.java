package com.trading.rsi.controller;

import com.trading.rsi.service.IGTradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IGTradingService tradingService;

    @BeforeEach
    void setUp() {
        when(tradingService.isAutoExecutionEnabled()).thenReturn(false);
        when(tradingService.isKillSwitchActive()).thenReturn(false);
    }

    @Test
    void status_returnsAutoExecutionAndKillSwitchState() throws Exception {
        mockMvc.perform(get("/api/trading/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.autoExecutionEnabled").value(false))
            .andExpect(jsonPath("$.killSwitchActive").value(false));
    }

    @Test
    void activateKillSwitch_returnsConfirmation() throws Exception {
        mockMvc.perform(post("/api/trading/kill-switch/activate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("KILL SWITCH ACTIVATED — all auto-trading stopped"));
    }

    @Test
    void deactivateKillSwitch_returnsConfirmation() throws Exception {
        mockMvc.perform(post("/api/trading/kill-switch/deactivate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("Kill switch deactivated"));
    }
}
