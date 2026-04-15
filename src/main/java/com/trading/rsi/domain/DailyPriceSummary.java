package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "daily_price_summary",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_daily_symbol_date",
        columnNames = {"symbol", "summary_date"}
    ),
    indexes = {
        @Index(name = "idx_daily_symbol", columnList = "symbol"),
        @Index(name = "idx_daily_date", columnList = "summary_date")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyPriceSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "instrument_name")
    private String instrumentName;

    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal closePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal volume;

    @Column(name = "candle_count")
    private Integer candleCount;
}
