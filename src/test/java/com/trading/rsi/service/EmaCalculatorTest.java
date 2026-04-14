package com.trading.rsi.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EmaCalculatorTest {

    private final EmaCalculator emaCalculator = new EmaCalculator();

    @Test
    void calculate_insufficientData_returnsNull() {
        List<BigDecimal> prices = List.of(new BigDecimal("100"), new BigDecimal("110"));
        assertNull(emaCalculator.calculate(prices, 50));
    }

    @Test
    void calculate_exactlyPeriodPrices_returnsSma() {
        // With exactly 'period' prices, EMA == SMA (no iterations after seed)
        List<BigDecimal> prices = List.of(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")
        );
        BigDecimal result = emaCalculator.calculate(prices, 3);
        assertNotNull(result);
        // SMA of 10, 20, 30 = 20
        assertEquals(0, result.compareTo(new BigDecimal("20.0000")));
    }

    @Test
    void calculate_upwardTrend_emaLagsPrice() {
        // Prices trending up — EMA should be below latest price
        List<BigDecimal> prices = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> new BigDecimal(i * 10))
                .toList();
        BigDecimal ema = emaCalculator.calculate(prices, 50);
        BigDecimal latest = prices.get(prices.size() - 1);
        assertNotNull(ema);
        assertTrue(ema.compareTo(latest) < 0, "EMA should be below latest price in uptrend");
    }

    @Test
    void isPriceAboveEma_uptrend_returnsTrue() {
        List<BigDecimal> prices = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> new BigDecimal(i * 10))
                .toList();
        Boolean result = emaCalculator.isPriceAboveEma(prices, 50);
        assertNotNull(result);
        assertTrue(result);
    }

    @Test
    void isPriceAboveEma_downtrend_returnsFalse() {
        // Prices trending down — last price below EMA
        List<BigDecimal> prices = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> new BigDecimal((61 - i) * 10))
                .toList();
        Boolean result = emaCalculator.isPriceAboveEma(prices, 50);
        assertNotNull(result);
        assertFalse(result);
    }

    @Test
    void isPriceAboveEma_insufficientData_returnsNull() {
        List<BigDecimal> prices = List.of(new BigDecimal("100"));
        assertNull(emaCalculator.isPriceAboveEma(prices, 50));
    }
}
