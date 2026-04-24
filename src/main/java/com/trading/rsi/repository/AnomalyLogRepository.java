package com.trading.rsi.repository;

import com.trading.rsi.domain.AnomalyLog;
import com.trading.rsi.model.AnomalyAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnomalyLogRepository extends JpaRepository<AnomalyLog, Long> {

    boolean existsBySymbolAndDetectedAtAfter(String symbol, Instant since);

    boolean existsBySeverityAndDetectedAtAfter(AnomalyAlert.Severity severity, Instant since);

    /**
     * Distinct symbols with anomalies since the cutoff, excluding CROSS_CORRELATION itself
     * so correlation alerts don't feed back into further correlation bursts.
     */
    @Query("SELECT DISTINCT a.symbol FROM AnomalyLog a WHERE a.detectedAt >= :since " +
           "AND a.symbol IS NOT NULL AND a.type <> com.trading.rsi.model.AnomalyAlert.AnomalyType.CROSS_CORRELATION")
    List<String> findDistinctSymbolsSince(@Param("since") Instant since);

    /**
     * True if a CROSS_CORRELATION anomaly has been logged since the given cutoff. Used to
     * avoid emitting multiple correlation alerts for the same burst.
     */
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM AnomalyLog a " +
           "WHERE a.type = com.trading.rsi.model.AnomalyAlert.AnomalyType.CROSS_CORRELATION " +
           "AND a.detectedAt >= :since")
    boolean existsCorrelationSince(@Param("since") Instant since);
}
