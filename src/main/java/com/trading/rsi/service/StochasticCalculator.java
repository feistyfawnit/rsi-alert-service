package com.trading.rsi.service;

import com.trading.rsi.model.Candle;
import com.trading.rsi.model.StochasticResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculates Stochastic Oscillator (%K and %D) from candle history.
 *
 * %K = (Close - LowestLow[period]) / (HighestHigh[period] - LowestLow[period]) × 100
 * %D = 3-period simple moving average of %K (signal line)
 *
 * Standard settings: period=14, smoothing=3.
 * Overbought: %K > 80. Oversold: %K < 20.
 */
@Service
public class StochasticCalculator {

    private static final int DEFAULT_PERIOD = 14;
    private static final int DEFAULT_SMOOTH = 3;

    public StochasticResult calculate(List<Candle> candles) {
        return calculate(candles, DEFAULT_PERIOD, DEFAULT_SMOOTH);
    }

    public StochasticResult calculate(List<Candle> candles, int period, int smooth) {
        if (candles == null || candles.size() < period + smooth - 1) {
            return null;
        }

        int size = candles.size();
        BigDecimal[] kValues = new BigDecimal[size - period + 1];

        for (int i = period - 1; i < size; i++) {
            BigDecimal high = BigDecimal.ZERO;
            BigDecimal low = null;
            for (int j = i - period + 1; j <= i; j++) {
                Candle c = candles.get(j);
                if (c.getHigh().compareTo(high) > 0) high = c.getHigh();
                if (low == null || c.getLow().compareTo(low) < 0) low = c.getLow();
            }
            BigDecimal range = high.subtract(low);
            BigDecimal k;
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                k = BigDecimal.valueOf(50);
            } else {
                k = candles.get(i).getClose().subtract(low)
                        .divide(range, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
            }
            kValues[i - period + 1] = k;
        }

        BigDecimal latestK = kValues[kValues.length - 1];

        BigDecimal dSum = BigDecimal.ZERO;
        for (int i = kValues.length - smooth; i < kValues.length; i++) {
            dSum = dSum.add(kValues[i]);
        }
        BigDecimal latestD = dSum.divide(BigDecimal.valueOf(smooth), 2, RoundingMode.HALF_UP);

        return new StochasticResult(latestK, latestD);
    }
}
