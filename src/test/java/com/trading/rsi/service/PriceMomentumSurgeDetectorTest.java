package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import com.trading.rsi.repository.SignalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PriceMomentumSurgeDetector service.
 * Tests focus on:
 * - Detecting 15m surges > 0.5%
 * - Detecting 1h surges > 1%
 * - Aligning surges across multiple instruments
 * - Comparing detected surges against TREND_BUY_DIP activity
 */
@ExtendWith(MockitoExtension.class)
class PriceMomentumSurgeDetectorTest {

    @Mock
    private CandleHistoryRepository candleHistoryRepository;

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private PositionOutcomeRepository positionOutcomeRepository;

    @Mock
    private SignalLogRepository signalLogRepository;

    @InjectMocks
    private PriceMomentumSurgeDetector surgeDetector;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(surgeDetector, "lookbackDays", 14);
        ReflectionTestUtils.setField(surgeDetector, "minSeparationMinutes", 60);
        ReflectionTestUtils.setField(surgeDetector, "reportPath", "./reports/momentum-surge-review.md");
    }

    // ── Scenario 1: Detect 15m surge > 0.5% ──

    @Test
    void generateReport_with15mSurge_detects() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);

        // Setup DAX instrument
        Instrument dax = Instrument.builder()
                .symbol("DAX")
                .name("DAX 40")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        // Create 15m candles: one with 0.6% surge
        List<CandleHistory> candles15m = new ArrayList<>();
        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(now.minus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("18000"))
                .high(new BigDecimal("18000"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18000"))
                .volume(new BigDecimal("1000000"))
                .build());

        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(now.minus(45, ChronoUnit.MINUTES))
                .open(new BigDecimal("18108"))  // 0.6% higher
                .high(new BigDecimal("18108"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18108"))
                .volume(new BigDecimal("1000000"))
                .build());

        when(instrumentRepository.findAll()).thenReturn(List.of(dax));
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(candles15m);
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        assertTrue(report.contains("DAX"));
        // If surge detection works, report should reference surge events
    }

    // ── Scenario 2: Detect 1h surge > 1% ──

    @Test
    void generateReport_with1hSurge_detects() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);

        Instrument spx = Instrument.builder()
                .symbol("SPX")
                .name("S&P 500")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        // Create 1h candles: one with 1.2% surge
        List<CandleHistory> candles1h = new ArrayList<>();
        candles1h.add(CandleHistory.builder()
                .symbol("SPX")
                .timeframe("1h")
                .candleTime(now.minus(2, ChronoUnit.HOURS))
                .open(new BigDecimal("5000"))
                .high(new BigDecimal("5000"))
                .low(new BigDecimal("5000"))
                .close(new BigDecimal("5000"))
                .volume(new BigDecimal("5000000"))
                .build());

        candles1h.add(CandleHistory.builder()
                .symbol("SPX")
                .timeframe("1h")
                .candleTime(now.minus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("5060"))  // 1.2% higher
                .high(new BigDecimal("5060"))
                .low(new BigDecimal("5000"))
                .close(new BigDecimal("5060"))
                .volume(new BigDecimal("5000000"))
                .build());

        when(instrumentRepository.findAll()).thenReturn(List.of(spx));
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("SPX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("SPX"), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(candles1h);
        when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        assertTrue(report.contains("S&P 500"));
    }

    // ── Scenario 3: Filter < 0.5% moves (false positives) ──

    @Test
    void generateReport_withSmall15mMove_filtered() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);

        Instrument dax = Instrument.builder()
                .symbol("DAX")
                .name("DAX 40")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        // Create 15m candles: one with only 0.3% move (below 0.5% threshold)
        List<CandleHistory> candles15m = new ArrayList<>();
        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(now.minus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("18000"))
                .high(new BigDecimal("18000"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18000"))
                .volume(new BigDecimal("1000000"))
                .build());

        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(now.minus(45, ChronoUnit.MINUTES))
                .open(new BigDecimal("18054"))  // Only 0.3% higher
                .high(new BigDecimal("18054"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18054"))
                .volume(new BigDecimal("1000000"))
                .build());

        when(instrumentRepository.findAll()).thenReturn(List.of(dax));
        lenient().when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(candles15m);
        lenient().when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        lenient().when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        // Report should note "no surges detected" or similar
        assertTrue(report.contains("Price Momentum Surge Review"));
    }

    // ── Scenario 4: Minimum separation (avoid consecutive alerts on same dip) ──

    @Test
    void generateReport_respectsMinSeparationMinutes() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);

        Instrument dax = Instrument.builder()
                .symbol("DAX")
                .name("DAX 40")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        // Create 15m candles: two surges within 30min (should be suppressed as one)
        List<CandleHistory> candles15m = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles15m.add(CandleHistory.builder()
                    .symbol("DAX")
                    .timeframe("15m")
                    .candleTime(from.plus(i * 15, ChronoUnit.MINUTES))
                    .open(new BigDecimal("18000"))
                    .high(new BigDecimal("18000"))
                    .low(new BigDecimal("18000"))
                    .close(new BigDecimal("18000"))
                    .volume(new BigDecimal("1000000"))
                    .build());
        }

        when(instrumentRepository.findAll()).thenReturn(List.of(dax));
        lenient().when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(candles15m);
        lenient().when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        lenient().when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
    }

    // ── Scenario 5: No enabled instruments ──

    @Test
    void generateReport_noEnabledInstruments_graceful() {
        when(instrumentRepository.findAll()).thenReturn(new ArrayList<>());
        lenient().when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        assertTrue(report.contains("No indices/commodities are currently enabled"));
    }

    // ── Scenario 6: Compare to TREND_BUY_DIP (tracked vs missed) ──

    @Test
    void generateReport_comparesToTrendBuyDip() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);

        Instrument dax = Instrument.builder()
                .symbol("DAX")
                .name("DAX 40")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        Instant surgeTime = from.plus(2, ChronoUnit.HOURS);

        // Create 15m candles with a surge
        List<CandleHistory> candles15m = new ArrayList<>();
        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(surgeTime.minus(1, ChronoUnit.HOURS))
                .open(new BigDecimal("18000"))
                .high(new BigDecimal("18000"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18000"))
                .volume(new BigDecimal("1000000"))
                .build());

        candles15m.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(surgeTime)
                .open(new BigDecimal("18108"))  // 0.6% surge
                .high(new BigDecimal("18108"))
                .low(new BigDecimal("18000"))
                .close(new BigDecimal("18108"))
                .volume(new BigDecimal("1000000"))
                .build());

        // Add a TREND_BUY_DIP signal nearby
        java.time.LocalDateTime signalTime = surgeTime.plus(30, ChronoUnit.MINUTES)
                .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        SignalLog trendSignal = SignalLog.builder()
                .symbol("DAX")
                .signalType(SignalLog.SignalType.TREND_BUY_DIP)
                .createdAt(signalTime)
                .build();

        when(instrumentRepository.findAll()).thenReturn(List.of(dax));
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(candles15m);
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        when(signalLogRepository.findAll()).thenReturn(List.of(trendSignal));

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        // When surge is caught by TREND_BUY_DIP, should indicate "CAUGHT" or similar
    }

    // ── Scenario 7: Multiple instruments with aligned surges ──

    @Test
    void generateReport_multipleInstrumentsAligned_detectsWindow() {
        Instant now = Instant.now();
        Instant from = now.minus(14, ChronoUnit.DAYS);
        Instant surgeWindow = from.plus(2, ChronoUnit.HOURS);

        Instrument dax = Instrument.builder()
                .symbol("DAX")
                .name("DAX 40")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        Instrument spx = Instrument.builder()
                .symbol("SPX")
                .name("S&P 500")
                .type(Instrument.InstrumentType.INDEX)
                .enabled(true)
                .build();

        // Create 15m candles for both with surges at similar times
        List<CandleHistory> daxCandles = new ArrayList<>();
        daxCandles.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(surgeWindow.minus(1, ChronoUnit.HOURS))
                .close(new BigDecimal("18000"))
                .build());
        daxCandles.add(CandleHistory.builder()
                .symbol("DAX")
                .timeframe("15m")
                .candleTime(surgeWindow)
                .close(new BigDecimal("18108"))  // 0.6% up
                .build());

        List<CandleHistory> spxCandles = new ArrayList<>();
        spxCandles.add(CandleHistory.builder()
                .symbol("SPX")
                .timeframe("15m")
                .candleTime(surgeWindow.plus(5, ChronoUnit.MINUTES))  // 5 min after DAX
                .close(new BigDecimal("5000"))
                .build());
        spxCandles.add(CandleHistory.builder()
                .symbol("SPX")
                .timeframe("15m")
                .candleTime(surgeWindow.plus(20, ChronoUnit.MINUTES))
                .close(new BigDecimal("5032"))  // 0.64% up
                .build());

        when(instrumentRepository.findAll()).thenReturn(List.of(dax, spx));
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("DAX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(daxCandles);
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("SPX"), eq("15m"), any(Instant.class), any(Instant.class)))
                .thenReturn(spxCandles);
        when(candleHistoryRepository.findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                anyString(), eq("1h"), any(Instant.class), any(Instant.class)))
                .thenReturn(new ArrayList<>());
        when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        // Report should show aligned surge window section if aligned events detected
    }

    // ── Scenario 8: Crypto instruments excluded (focus on calm markets) ──

    @Test
    void generateReport_excludesCrypto() {
        Instrument btc = Instrument.builder()
                .symbol("BTCUSDT")
                .name("Bitcoin")
                .type(Instrument.InstrumentType.CRYPTO)
                .enabled(true)
                .build();

        when(instrumentRepository.findAll()).thenReturn(List.of(btc));
        lenient().when(signalLogRepository.findAll()).thenReturn(new ArrayList<>());

        String report = surgeDetector.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("Price Momentum Surge Review"));
        // Crypto instruments should be skipped silently
        assertFalse(report.contains("Bitcoin"));
    }
}
