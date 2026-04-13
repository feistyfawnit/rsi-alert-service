package com.trading.rsi;

import com.trading.rsi.domain.Instrument;
import com.trading.rsi.model.Candle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shared test fixtures for reuse across test classes.
 * Reduces duplication of common object creation patterns.
 */
public class TestFixtures {

    public static Candle createCandle(double close) {
        return createCandle(close, 1000.0, close - 1, close + 1);
    }

    public static Candle createCandle(double close, double volume) {
        return createCandle(close, volume, close - 1, close + 1);
    }

    public static Candle createCandle(double close, double volume, double open, double high) {
        return Candle.builder()
            .open(BigDecimal.valueOf(open))
            .high(BigDecimal.valueOf(high))
            .low(BigDecimal.valueOf(open - 2))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(volume))
            .timestamp(Instant.now())
            .build();
    }

    public static Candle createBullishCandle(double open, double close) {
        double high = Math.max(open, close) + 1;
        double low = Math.min(open, close) - 1;
        return Candle.builder()
            .open(BigDecimal.valueOf(open))
            .high(BigDecimal.valueOf(high))
            .low(BigDecimal.valueOf(low))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(1000))
            .timestamp(Instant.now())
            .build();
    }

    public static Candle createBearishCandle(double open, double close) {
        double high = Math.max(open, close) + 1;
        double low = Math.min(open, close) - 1;
        return Candle.builder()
            .open(BigDecimal.valueOf(open))
            .high(BigDecimal.valueOf(high))
            .low(BigDecimal.valueOf(low))
            .close(BigDecimal.valueOf(close))
            .volume(BigDecimal.valueOf(1000))
            .timestamp(Instant.now())
            .build();
    }

    public static Instrument createInstrument(String symbol) {
        return createInstrument(symbol, symbol + " Name", true);
    }

    public static Instrument createInstrument(String symbol, String name, boolean enabled) {
        return Instrument.builder()
            .symbol(symbol)
            .name(name)
            .source(Instrument.DataSource.BINANCE)
            .type(Instrument.InstrumentType.CRYPTO)
            .enabled(enabled)
            .timeframes("15m,1h,4h")
            .oversoldThreshold(30)
            .overboughtThreshold(70)
            .build();
    }

    public static Instrument createIgInstrument(String symbol, String name) {
        return Instrument.builder()
            .symbol(symbol)
            .name(name)
            .source(Instrument.DataSource.IG)
            .type(Instrument.InstrumentType.INDEX)
            .enabled(true)
            .timeframes("15m,30m,1h")
            .oversoldThreshold(30)
            .overboughtThreshold(70)
            .build();
    }
}
