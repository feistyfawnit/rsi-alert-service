package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.repository.CandleHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Computes the MACD (Moving Average Convergence Divergence) indicator
 * — Gerald Appel, 1979 — for a symbol on a given timeframe.
 *
 * MACD line     = EMA(close, fast) − EMA(close, slow)        e.g. EMA12 − EMA26
 * Signal line   = EMA(MACD line, signal)                      e.g. EMA9 of MACD
 * Histogram     = MACD line − Signal line
 *
 * For a TREND_BUY_DIP confirmation we only care about the histogram:
 *   histogram &gt; 0           → bullish momentum is intact
 *   histogram &gt; previous    → bullish momentum is rising (turning up from a dip)
 * Either condition passes the filter.
 *
 * Needs at least slow + signal + 1 candles to produce a usable histogram pair.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MacdCalculator {

    private final CandleHistoryRepository candleHistoryRepository;

    /** Latest histogram value and its previous value (one candle earlier). */
    public record MacdHistogram(BigDecimal current, BigDecimal previous) {
        public boolean isBullish() {
            return current.signum() > 0;
        }
        public boolean isRising() {
            return current.compareTo(previous) > 0;
        }
    }

    public Optional<MacdHistogram> compute(String symbol, String timeframe,
                                            int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || signalPeriod < 2) {
            return Optional.empty();
        }

        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeAsc(symbol, timeframe);

        // We need enough bars so that:
        //  - the slow EMA is seeded (slow bars), then
        //  - we generate enough MACD-line values to seed the signal EMA (signal bars), then
        //  - we have at least 2 histogram values (current + previous).
        int required = slowPeriod + signalPeriod + 1;
        if (candles.size() < required) {
            log.debug("MACD unavailable for {}:{} — only {} candles (need {})",
                    symbol, timeframe, candles.size(), required);
            return Optional.empty();
        }

        int n = candles.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = candles.get(i).getClose().doubleValue();
        }

        double[] fastEma = ema(closes, fastPeriod);
        double[] slowEma = ema(closes, slowPeriod);

        // MACD line is only valid from index slowPeriod-1 onward (where slow EMA is seeded).
        int macdStart = slowPeriod - 1;
        int macdLen = n - macdStart;
        double[] macdLine = new double[macdLen];
        for (int i = 0; i < macdLen; i++) {
            macdLine[i] = fastEma[macdStart + i] - slowEma[macdStart + i];
        }

        if (macdLen < signalPeriod + 1) {
            return Optional.empty();
        }

        double[] signalEma = ema(macdLine, signalPeriod);
        // Histogram is valid where the signal EMA is seeded (index signalPeriod-1 onward in macdLine space).
        int last = macdLen - 1;
        int prev = macdLen - 2;
        double histLast = macdLine[last] - signalEma[last];
        double histPrev = macdLine[prev] - signalEma[prev];

        return Optional.of(new MacdHistogram(
                BigDecimal.valueOf(histLast).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(histPrev).setScale(6, RoundingMode.HALF_UP)));
    }

    /**
     * Standard EMA series, seeded with an SMA of the first `period` values.
     * For indices &lt; period-1 the value is the seed SMA (placeholder; callers should
     * not consume those indices).
     */
    private double[] ema(double[] values, int period) {
        double k = 2.0 / (period + 1);
        double[] out = new double[values.length];
        if (values.length < period) return out;

        double sum = 0;
        for (int i = 0; i < period; i++) sum += values[i];
        double seed = sum / period;
        for (int i = 0; i < period; i++) out[i] = seed; // placeholder; not used by callers
        out[period - 1] = seed;

        double ema = seed;
        for (int i = period; i < values.length; i++) {
            ema = values[i] * k + ema * (1 - k);
            out[i] = ema;
        }
        return out;
    }
}
