package com.trading.rsi.controller;

import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.SignalLogRepository;
import com.trading.rsi.service.MarketDataService;
import com.trading.rsi.service.NotificationService;
import com.trading.rsi.service.PriceHistoryService;
import com.trading.rsi.service.RsiCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SignalLogRepository signalLogRepository;

    @MockitoBean
    private InstrumentRepository instrumentRepository;

    @MockitoBean
    private PriceHistoryService priceHistoryService;

    @MockitoBean
    private RsiCalculator rsiCalculator;

    @MockitoBean
    private MarketDataService marketDataService;

    @MockitoBean
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        when(signalLogRepository.findAll()).thenReturn(Collections.emptyList());
        when(signalLogRepository.findBySymbolOrderByCreatedAtDesc(any())).thenReturn(Collections.emptyList());
        when(signalLogRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(Collections.emptyList());
        when(instrumentRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());
    }

    @Test
    void getAllSignals_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/signals"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    void getRecentSignals_returnsFilteredByHours() throws Exception {
        mockMvc.perform(get("/api/signals/recent").param("hours", "24"))
            .andExpect(status().isOk());
    }

    @Test
    void getSignalsBySymbol_usesCorrectPath() throws Exception {
        mockMvc.perform(get("/api/signals/symbol/BTCUSDT"))
            .andExpect(status().isOk());
    }

    @Test
    void getRsiSnapshot_returnsMap() throws Exception {
        mockMvc.perform(get("/api/signals/rsi-snapshot"))
            .andExpect(status().isOk());
    }
}
