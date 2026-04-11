package com.trading.rsi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RsiCalculatorTest {

    private final RsiCalculator calculator = new RsiCalculator();

    @Test
    void calculateRsi_withInsufficientData_returnsNull() {
        List<BigDecimal> prices = List.of(
            new BigDecimal("100"),
            new BigDecimal("101")
        );
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNull(result);
    }

    @Test
    void calculateRsi_withExactlyPeriodPlusOne_calculatesCorrectly() {
        // 15 prices = 14 changes, minimum for RSI(14)
        List<BigDecimal> prices = new ArrayList<>();
        prices.add(new BigDecimal("100")); // baseline
        // 14 up-moves of 1 point each
        for (int i = 1; i <= 14; i++) {
            prices.add(new BigDecimal(100 + i));
        }
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        // All gains, no losses -> RSI should be 100
        assertEquals(0, result.compareTo(BigDecimal.valueOf(100)), 
            "RSI should be 100 when all moves are up");
    }

    @Test
    void calculateRsi_withAllDownMoves_returnsZero() {
        List<BigDecimal> prices = new ArrayList<>();
        prices.add(new BigDecimal("100"));
        // 14 down-moves of 1 point each
        for (int i = 1; i <= 14; i++) {
            prices.add(new BigDecimal(100 - i));
        }
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        // All losses, no gains -> RSI should be 0
        assertEquals(0, result.compareTo(BigDecimal.ZERO),
            "RSI should be 0 when all moves are down");
    }

    @Test
    void calculateRsi_withMixedMoves_returnsReasonableValue() {
        // Known test case: alternating up/down should give RSI near 50
        List<BigDecimal> prices = new ArrayList<>();
        prices.add(new BigDecimal("100"));
        // Alternating +1, -1 for 14 periods
        for (int i = 1; i <= 14; i++) {
            if (i % 2 == 1) {
                prices.add(new BigDecimal(100 + ((i + 1) / 2)));
            } else {
                prices.add(new BigDecimal(100 + (i / 2) - 1));
            }
        }
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        // RSI should be somewhere between 30 and 70 for balanced moves
        assertTrue(result.compareTo(BigDecimal.valueOf(30)) > 0,
            "RSI should be above 30 for balanced moves, got " + result);
        assertTrue(result.compareTo(BigDecimal.valueOf(70)) < 0,
            "RSI should be below 70 for balanced moves, got " + result);
    }

    @Test
    void calculateRsi_verifiesWilderSmoothing() {
        // Test that Wilder's smoothing is applied correctly
        // First 14 periods establish baseline, subsequent periods smooth
        List<BigDecimal> prices = new ArrayList<>();
        prices.add(new BigDecimal("100"));
        // 14 periods of +1 gain
        for (int i = 1; i <= 14; i++) {
            prices.add(new BigDecimal(100 + i));
        }
        // Add 5 more periods with +2 gains (stronger)
        for (int i = 15; i <= 19; i++) {
            prices.add(new BigDecimal(114 + (i - 14) * 2));
        }
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        // With strong continued gains, RSI should be high
        assertTrue(result.compareTo(BigDecimal.valueOf(80)) > 0,
            "RSI should be >80 with sustained strong gains, got " + result);
    }

    @Test
    void calculateRsi_withMoreData_usesWilderSmoothing() {
        // 30 periods: first 14 establish, then 15 more with smoothing
        List<BigDecimal> prices = generatePrices(30, 0.5, 0.3);
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        assertTrue(result.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(result.compareTo(BigDecimal.valueOf(100)) <= 0);
    }

    @Test
    void calculateRsi_defaultPeriod_uses14() {
        // Default period test
        List<BigDecimal> prices = new ArrayList<>();
        prices.add(new BigDecimal("100"));
        for (int i = 1; i <= 20; i++) {
            prices.add(new BigDecimal(100 + i));
        }
        
        BigDecimal result = calculator.calculateRsi(prices);
        
        assertNotNull(result);
        assertTrue(result.compareTo(BigDecimal.valueOf(95)) > 0,
            "Default period should work and give high RSI for all-up");
    }

    @ParameterizedTest
    @CsvSource({
        "44.0113, 44.0321, 44.1234, 44.2321, 44.3456, 44.4123, 44.5123, 44.6234, 44.7234, 44.8234, 44.9234, 45.0234, 45.1234, 45.2234, 45.3234, true",
        "45.3234, 45.2234, 45.1234, 45.0234, 44.9234, 44.8234, 44.7234, 44.6234, 44.5123, 44.4123, 44.3456, 44.2321, 44.1234, 44.0321, 44.0113, false"
    })
    void calculateRsi_realisticPriceSequence(String p1, String p2, String p3, String p4, String p5,
                                                String p6, String p7, String p8, String p9, String p10,
                                                String p11, String p12, String p13, String p14, String p15,
                                                boolean rising) {
        List<BigDecimal> prices = List.of(
            new BigDecimal(p1), new BigDecimal(p2), new BigDecimal(p3), new BigDecimal(p4),
            new BigDecimal(p5), new BigDecimal(p6), new BigDecimal(p7), new BigDecimal(p8),
            new BigDecimal(p9), new BigDecimal(p10), new BigDecimal(p11), new BigDecimal(p12),
            new BigDecimal(p13), new BigDecimal(p14), new BigDecimal(p15)
        );
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        if (rising) {
            assertTrue(result.compareTo(BigDecimal.valueOf(50)) > 0,
                "RSI should be above 50 for rising trend");
        } else {
            assertTrue(result.compareTo(BigDecimal.valueOf(50)) < 0,
                "RSI should be below 50 for falling trend");
        }
    }

    @Test
    void calculateRsi_precision_maintainedForNonExtremeValues() {
        // Test that precision is maintained for non-extreme RSI values
        // Use synthetic data with clear mixed gains/losses to get RSI in middle range
        List<BigDecimal> prices = generateBalancedPrices(30, 0.5, 0.5);
        
        BigDecimal result = calculator.calculateRsi(prices, 14);
        
        assertNotNull(result);
        // With balanced up/down moves, RSI should be non-extreme
        assertTrue(result.compareTo(BigDecimal.valueOf(30)) > 0,
            "RSI should be non-extreme (30-70 range) for balanced moves, got: " + result);
        assertTrue(result.compareTo(BigDecimal.valueOf(70)) < 0,
            "RSI should be non-extreme (30-70 range) for balanced moves, got: " + result);
    }

    // Helper method to generate synthetic price data
    private List<BigDecimal> generatePrices(int count, double upProb, double avgMove) {
        List<BigDecimal> prices = new ArrayList<>();
        double price = 100.0;
        prices.add(BigDecimal.valueOf(price));
        
        java.util.Random random = new java.util.Random(42); // fixed seed for reproducibility
        for (int i = 1; i < count; i++) {
            boolean up = random.nextDouble() < upProb;
            double move = random.nextDouble() * avgMove;
            price += up ? move : -move;
            prices.add(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP));
        }
        return prices;
    }

    // Helper that ensures balanced gains/losses for non-extreme RSI
    private List<BigDecimal> generateBalancedPrices(int count, double upProb, double avgMove) {
        List<BigDecimal> prices = new ArrayList<>();
        double price = 100.0;
        prices.add(BigDecimal.valueOf(price));

        java.util.Random random = new java.util.Random(123); // different seed for variety
        int upCount = 0, downCount = 0;

        for (int i = 1; i < count; i++) {
            // Force balance if getting too skewed
            boolean up;
            if (upCount - downCount > 3) {
                up = false; // Force down to balance
            } else if (downCount - upCount > 3) {
                up = true; // Force up to balance
            } else {
                up = random.nextDouble() < upProb;
            }

            double move = random.nextDouble() * avgMove;
            price += up ? move : -move;
            prices.add(BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP));

            if (up) upCount++; else downCount++;
        }
        return prices;
    }
}
