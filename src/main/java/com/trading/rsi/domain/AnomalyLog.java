package com.trading.rsi.domain;

import com.trading.rsi.model.AnomalyAlert;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "anomaly_log",
    indexes = {
        @Index(name = "idx_anomaly_symbol_time", columnList = "symbol,detected_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnomalyAlert.AnomalyType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AnomalyAlert.Severity severity;

    @Column(length = 30)
    private String symbol;

    @Column(nullable = false)
    private String description;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
