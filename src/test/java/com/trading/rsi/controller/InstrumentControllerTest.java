package com.trading.rsi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstrumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InstrumentRepository instrumentRepository;

    @Test
    void getAllInstruments_returnsList() throws Exception {
        Instrument instrument = Instrument.builder()
            .id(1L)
            .symbol("BTCUSDT")
            .name("Bitcoin")
            .source(Instrument.DataSource.BINANCE)
            .build();

        when(instrumentRepository.findAll()).thenReturn(List.of(instrument));

        mockMvc.perform(get("/api/instruments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"));
    }

    @Test
    void getEnabledInstruments_returnsOnlyEnabled() throws Exception {
        Instrument enabled = Instrument.builder()
            .id(1L)
            .symbol("BTCUSDT")
            .enabled(true)
            .source(Instrument.DataSource.BINANCE)
            .build();
        when(instrumentRepository.findByEnabledTrue()).thenReturn(List.of(enabled));

        mockMvc.perform(get("/api/instruments/enabled"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"));
    }

    @Test
    void createInstrument_savesAndReturns() throws Exception {
        Instrument input = Instrument.builder()
            .symbol("SOLUSDT")
            .name("Solana")
            .source(Instrument.DataSource.BINANCE)
            .type(Instrument.InstrumentType.CRYPTO)
            .enabled(true)
            .timeframes("15m,1h,4h")
            .oversoldThreshold(30)
            .overboughtThreshold(70)
            .build();

        Instrument saved = Instrument.builder()
            .id(1L)
            .symbol("SOLUSDT")
            .name("Solana")
            .source(Instrument.DataSource.BINANCE)
            .type(Instrument.InstrumentType.CRYPTO)
            .enabled(true)
            .timeframes("15m,1h,4h")
            .oversoldThreshold(30)
            .overboughtThreshold(70)
            .build();

        when(instrumentRepository.save(any(Instrument.class))).thenReturn(saved);

        mockMvc.perform(post("/api/instruments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.symbol").value("SOLUSDT"));
    }

    @Test
    void getInstrumentById_found_returnsInstrument() throws Exception {
        Instrument instrument = Instrument.builder()
            .id(1L)
            .symbol("BTCUSDT")
            .name("Bitcoin")
            .source(Instrument.DataSource.BINANCE)
            .build();

        when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));

        mockMvc.perform(get("/api/instruments/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("BTCUSDT"));
    }

    @Test
    void getInstrumentById_notFound_returns404() throws Exception {
        when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/instruments/999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void toggleInstrument_switchesEnabledState() throws Exception {
        Instrument instrument = Instrument.builder()
            .id(1L)
            .symbol("BTCUSDT")
            .enabled(true)
            .source(Instrument.DataSource.BINANCE)
            .build();

        when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
        when(instrumentRepository.save(any(Instrument.class))).thenAnswer(i -> i.getArgument(0));

        mockMvc.perform(patch("/api/instruments/1/toggle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
    }
}
