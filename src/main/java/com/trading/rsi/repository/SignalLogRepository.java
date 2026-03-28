package com.trading.rsi.repository;

import com.trading.rsi.domain.SignalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {
    List<SignalLog> findBySymbolOrderByCreatedAtDesc(String symbol);
    List<SignalLog> findByCreatedAtAfter(LocalDateTime after);
}
