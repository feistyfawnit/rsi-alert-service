package com.trading.rsi.repository;

import com.trading.rsi.domain.SignalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SignalLogRepository extends JpaRepository<SignalLog, Long> {
    List<SignalLog> findBySymbolOrderByCreatedAtDesc(String symbol);
    List<SignalLog> findByCreatedAtAfter(LocalDateTime after);
    List<SignalLog> findByCreatedAtBefore(LocalDateTime before);
    Optional<SignalLog> findFirstBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(String symbol, LocalDateTime after);
}
