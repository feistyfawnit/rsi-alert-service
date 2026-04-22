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
 * Computes ATR (Average True Range) over N periods using Wilder's smoothing
 * on the finest available candle timeframe for a symbol.
 *
 * ATR gives a volatility-adjusted stop distance. A 1.5 × ATR stop adapts
 * automatically to current market conditions: tighter in calm regimes,
 * wider in volatile regimes. This replaces the arbitrary fixed-pct stops.
 *
 * True Range for candle i = max(
 *   high_i - low_i,
 *   |high_i - close_{i-1}|,
 *   |low_i  - close_{i-1}|
 * )
 *
 * ATR_0    = mean(TR over first N bars)
 * ATR_i    = (ATR_{i-1} * (N - 1) + TR_i) / N   (Wilder smoothing)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AtrCalculator {

    private final CandleHistoryRepository candleHistoryRepository;

    /**
     * Compute ATR for a symbol+timeframe.
     *
     * @param symbol    instrument symbol (e.g. SOLUSDT, IX.D.DAX.DAILY.IP)
     * @param timeframe timeframe to sample (e.g. "15m")
     * @param period    ATR period (typically 14)
     * @return Optional of the latest ATR value, empty if insufficient candles
     */
    public Optional<BigDecimal> computeAtr(String symbol, String timeframe, int period) {
        if (period < 2) return Optional.empty();

        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeAsc(symbol, timeframe);

        // Need at least period+1 candles (one prior close for first TR)
        if (candles.size() < period + 1) {
            log.debug("ATR unavailable for {}:{} — only {} candles (need {})",
                    symbol, timeframe, candles.size(), period + 1);
            return Optional.empty();
        }

        // Compute true ranges
        double[] trs = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            CandleHistory cur = candles.get(i);
            double prevClose = candles.get(i - 1).getClose().doubleValue();
            double h = cur.getHigh().doubleValue();
            double l = cur.getLow().doubleValue();
            double hl = h - l;
            double hc = Math.abs(h - prevClose);
            double lc = Math.abs(l - prevClose);
            trs[i - 1] = Math.max(hl, Math.max(hc, lc));
        }

        // Seed ATR from mean of first `period` TRs
        double atr = 0;
        for (int i = 0; i < period; i++) atr += trs[i];
        atr /= period;

        // Wilder smoothing over the rest
        for (int i = period; i < trs.length; i++) {
            atr = (atr * (period - 1) + trs[i]) / period;
        }

        return Optional.of(BigDecimal.valueOf(atr).setScale(8, RoundingMode.HALF_UP));
    }

    /**
     * Compute current ATR relative to its own rolling average — useful for
     * a volatility-spike filter (e.g. skip signals when ratio > 1.5).
     * Returns empty if insufficient data.
     */
    public Optional<Double> atrExpansionRatio(String symbol, String timeframe,
                                              int period, int avgLookback) {
        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeOrderByCandleTimeAsc(symbol, timeframe);
        if (candles.size() < period + avgLookback + 1) return Optional.empty();

        // Build TRs
        double[] trs = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            CandleHistory cur = candles.get(i);
            double prevClose = candles.get(i - 1).getClose().doubleValue();
            double h = cur.getHigh().doubleValue();
            double l = cur.getLow().doubleValue();
            trs[i - 1] = Math.max(h - l, Math.max(Math.abs(h - prevClose), Math.abs(l - prevClose)));
        }

        // Walk ATR forward, tracking the last `avgLookback` ATR values
        double atr = 0;
        for (int i = 0; i < period; i++) atr += trs[i];
        atr /= period;

        double[] atrSeries = new double[trs.length - period + 1];
        atrSeries[0] = atr;
        for (int i = period; i < trs.length; i++) {
            atr = (atr * (period - 1) + trs[i]) / period;
            atrSeries[i - period + 1] = atr;
        }

        double current = atrSeries[atrSeries.length - 1];
        int fromIdx = Math.max(0, atrSeries.length - avgLookback);
        double sum = 0; int n = 0;
        for (int i = fromIdx; i < atrSeries.length; i++) { sum += atrSeries[i]; n++; }
        double avg = n == 0 ? 0 : sum / n;
        if (avg <= 0) return Optional.empty();
        return Optional.of(current / avg);
    }
}
