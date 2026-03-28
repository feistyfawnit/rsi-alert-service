package com.trading.rsi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "instruments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Instrument {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String name;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstrumentType type;
    
    @Column(nullable = false)
    private Boolean enabled = true;
    
    @Column(name = "oversold_threshold")
    private Integer oversoldThreshold = 30;
    
    @Column(name = "overbought_threshold")
    private Integer overboughtThreshold = 70;
    
    @Column(name = "timeframes")
    private String timeframes = "1m,5m,1h,4h";
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public enum DataSource {
        BINANCE,
        FINNHUB,
        IG,
        TWELVE_DATA
    }
    
    public enum InstrumentType {
        CRYPTO,
        INDEX,
        COMMODITY,
        FX
    }
}
