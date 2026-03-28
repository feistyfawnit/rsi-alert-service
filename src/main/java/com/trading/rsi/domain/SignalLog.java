package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "signal_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String instrumentName;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalType signalType;
    
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal currentPrice;
    
    @Column(name = "rsi_1m", precision = 10, scale = 4)
    private BigDecimal rsi1m;
    
    @Column(name = "rsi_5m", precision = 10, scale = 4)
    private BigDecimal rsi5m;
    
    @Column(name = "rsi_1h", precision = 10, scale = 4)
    private BigDecimal rsi1h;
    
    @Column(name = "rsi_4h", precision = 10, scale = 4)
    private BigDecimal rsi4h;
    
    @Column(name = "timeframes_aligned")
    private Integer timeframesAligned;
    
    @Column(name = "signal_strength", precision = 10, scale = 4)
    private BigDecimal signalStrength;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    public enum SignalType {
        OVERSOLD,
        OVERBOUGHT,
        PARTIAL_OVERSOLD,
        PARTIAL_OVERBOUGHT
    }
}
