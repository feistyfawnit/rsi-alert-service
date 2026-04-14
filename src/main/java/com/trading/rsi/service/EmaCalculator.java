package com.trading.rsi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculates Exponential Moving Average (EMA) from a price series.
 *
 * EMA gives more weight to recent prices than a simple moving average.
 * Formula: EMA(t) = price(t) * k + EMA(t-1) * (1 - k), where k = 2 / (period + 1)
 *
 * Primary use: EMA 50 on the 1h timeframe as a trend filter.
 * If current price > EMA50(1h) → uptrend. If price < EMA50(1h) → downtrend.
 */
@Service
@Slf4j
public class EmaCalculator {

    /**
     * Calculate EMA for the given period from a list of closing prices (oldest first).
     * Returns null if insufficient data (need at least {@code period} values).
     */
    public BigDecimal calculate(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) {
            return null;
        }

        double k = 2.0 / (period + 1);

        // Seed with SMA of first 'period' values
        double ema = prices.subList(0, period).stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0);

        for (int i = period; i < prices.size(); i++) {
            ema = prices.get(i).doubleValue() * k + ema * (1 - k);
        }

        return BigDecimal.valueOf(ema).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Returns true if the current price is above the EMA for the given period.
     * Returns null (unknown) if insufficient data to calculate EMA.
     */
    public Boolean isPriceAboveEma(List<BigDecimal> prices, int period) {
        if (prices == null || prices.isEmpty()) return null;
        BigDecimal ema = calculate(prices, period);
        if (ema == null) return null;
        BigDecimal currentPrice = prices.get(prices.size() - 1);
        return currentPrice.compareTo(ema) > 0;
    }
}
