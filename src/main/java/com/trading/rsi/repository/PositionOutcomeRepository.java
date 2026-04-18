package com.trading.rsi.repository;

import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PositionOutcomeRepository extends JpaRepository<PositionOutcome, Long> {

    List<PositionOutcome> findByExitTimeIsNull();

    List<PositionOutcome> findByExitTimeIsNotNull();

    List<PositionOutcome> findBySignalTypeAndExitTimeIsNotNull(SignalLog.SignalType signalType);
}
