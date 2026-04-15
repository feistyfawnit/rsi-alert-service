package com.trading.rsi.repository;

import com.trading.rsi.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    List<Instrument> findByEnabledTrue();
    boolean existsBySymbol(String symbol);
    Optional<Instrument> findBySymbol(String symbol);
    List<Instrument> findBySymbolNotIn(List<String> symbols);
}
