package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "candle_history",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_candle_symbol_timeframe_time",
        columnNames = {"symbol", "timeframe", "candle_time"}
    ),
    indexes = {
        @Index(name = "idx_candle_symbol_timeframe", columnList = "symbol, timeframe"),
        @Index(name = "idx_candle_time", columnList = "candle_time")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String timeframe;

    @Column(name = "candle_time", nullable = false)
    private Instant candleTime;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    @Column(precision = 20, scale = 8)
    private BigDecimal volume;
}
