package com.trading.rsi.repository;

import com.trading.rsi.domain.AnomalyLog;
import com.trading.rsi.model.AnomalyAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AnomalyLogRepository extends JpaRepository<AnomalyLog, Long> {

    boolean existsBySymbolAndDetectedAtAfter(String symbol, Instant since);

    boolean existsBySeverityAndDetectedAtAfter(AnomalyAlert.Severity severity, Instant since);
}
