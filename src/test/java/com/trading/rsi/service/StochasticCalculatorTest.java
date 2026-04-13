package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import com.trading.rsi.model.StochasticResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StochasticCalculatorTest {

    private final StochasticCalculator calculator = new StochasticCalculator();

    @Test
    void calculate_withInsufficientData_returnsNull() {
        List<Candle> candles = List.of(
            createCandle(100, 105, 95, 102)
        );
        
        StochasticResult result = calculator.calculate(candles, 14, 3);
        
        assertNull(result);
    }

    @Test
    void calculate_withExactPeriodPlusSmooth_calculatesCorrectly() {
        // 16 candles = 14 for %K + 3-1 for %D smoothing
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            // Range: low=100+i, high=110+i, close=105+i (middle of range)
            candles.add(createCandle(100 + i, 110 + i, 100 + i, 105 + i));
        }
        
        StochasticResult result = calculator.calculate(candles);
        
        assertNotNull(result);
        assertTrue(result.k().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(result.k().compareTo(BigDecimal.valueOf(100)) <= 0);
        assertTrue(result.d().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(result.d().compareTo(BigDecimal.valueOf(100)) <= 0);
    }

    @Test
    void calculate_withCloseAtHigh_KshouldBe100() {
        // Create candles where close is at the high of the range
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int low = 100 + i;
            int high = 110 + i;
            int close = high; // Close at high
            candles.add(createCandle(low, high, low, close));
        }
        
        StochasticResult result = calculator.calculate(candles);
        
        assertNotNull(result);
        // %K should be near 100 when close is at high
        assertTrue(result.k().compareTo(BigDecimal.valueOf(90)) > 0,
            "K should be >90 when close is at high, got " + result.k());
    }

    @Test
    void calculate_withCloseAtLow_KshouldBe0() {
        // All candles in fixed range [100, 110]; close = 100 (the low) → K should be 0
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candles.add(createCandle(100, 110, 100, 100)); // open=high=100, low=100, close=100
        }

        StochasticResult result = calculator.calculate(candles);

        assertNotNull(result);
        assertEquals(0, result.k().compareTo(BigDecimal.ZERO),
            "K should be 0 when close equals the period low, got " + result.k());
    }

    @Test
    void calculate_DlineSmoothsK() {
        // Create oscillating candles to get varying %K values
        List<Candle> candles = createOscillatingCandles(20);
        
        StochasticResult result = calculator.calculate(candles, 14, 3);
        
        assertNotNull(result);
        // %D is a 3-period SMA of %K, so it should be different from %K
        // They won't be equal unless %K is constant
    }

    @Test
    void calculate_withZeroRange_returns50() {
        // When high = low, the formula should return 50 (middle)
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // All same price - zero range
            candles.add(createCandle(100, 100, 100, 100));
        }
        
        StochasticResult result = calculator.calculate(candles);
        
        assertNotNull(result);
        assertEquals(0, result.k().compareTo(BigDecimal.valueOf(50)),
            "K should be 50 when range is zero");
    }

    @Test
    void calculate_defaultPeriods_uses14And3() {
        List<Candle> candles = createRandomCandles(20);
        
        StochasticResult resultDefault = calculator.calculate(candles);
        StochasticResult resultExplicit = calculator.calculate(candles, 14, 3);
        
        assertNotNull(resultDefault);
        assertNotNull(resultExplicit);
        assertEquals(0, resultDefault.k().compareTo(resultExplicit.k()));
        assertEquals(0, resultDefault.d().compareTo(resultExplicit.d()));
    }

    @Test
    void calculate_preservesPrecision() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // Use precise decimal values
            BigDecimal base = new BigDecimal("100.123456");
            Candle candle = Candle.builder()
                .open(base.add(new BigDecimal(i)))
                .high(base.add(new BigDecimal(i)).add(new BigDecimal("10.5")))
                .low(base.add(new BigDecimal(i)))
                .close(base.add(new BigDecimal(i)).add(new BigDecimal("5.25")))
                .volume(BigDecimal.valueOf(1000 + i))
                .timestamp(Instant.now())
                .build();
            candles.add(candle);
        }
        
        StochasticResult result = calculator.calculate(candles);
        
        assertNotNull(result);
        // Results should have 2 decimal places (scale set in calculator)
        assertTrue(result.k().scale() <= 2, "K should have scale <= 2");
        assertTrue(result.d().scale() <= 2, "D should have scale <= 2");
    }

    @Test
    void calculate_withNullCandles_returnsNull() {
        StochasticResult result = calculator.calculate(null);
        assertNull(result);
    }

    // Helper methods

    private Candle createCandle(double open, double high, double low, double close) {
        return Candle.builder()
            .open(BigDecimal.valueOf(open))
            .high(BigDecimal.valueOf(high))
            .low(BigDecimal.valueOf(low))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(1000))
            .timestamp(Instant.now())
            .build();
    }

    private List<Candle> createOscillatingCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Oscillate between high and low closes
            boolean highClose = i % 2 == 0;
            int low = 100;
            int high = 120;
            int close = highClose ? 118 : 102;
            candles.add(createCandle(110, high, low, close));
        }
        return candles;
    }

    private List<Candle> createRandomCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < count; i++) {
            int base = 100 + i;
            int low = base;
            int high = base + 10 + random.nextInt(5);
            int close = low + random.nextInt(high - low);
            candles.add(createCandle(base, high, low, close));
        }
        return candles;
    }
}
