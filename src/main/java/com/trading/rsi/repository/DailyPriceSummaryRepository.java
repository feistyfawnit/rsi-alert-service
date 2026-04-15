package com.trading.rsi.repository;

import com.trading.rsi.domain.DailyPriceSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyPriceSummaryRepository extends JpaRepository<DailyPriceSummary, Long> {

    Optional<DailyPriceSummary> findBySymbolAndSummaryDate(String symbol, LocalDate summaryDate);

    List<DailyPriceSummary> findBySymbolOrderBySummaryDateAsc(String symbol);

    List<DailyPriceSummary> findBySymbolAndSummaryDateBetweenOrderBySummaryDateAsc(
            String symbol, LocalDate from, LocalDate to);

    List<DailyPriceSummary> findBySummaryDateOrderBySymbolAsc(LocalDate summaryDate);

    List<DailyPriceSummary> findAllByOrderBySymbolAscSummaryDateAsc();

    List<DailyPriceSummary> findBySummaryDateBeforeOrderBySymbolAscSummaryDateAsc(LocalDate before);

    void deleteBySummaryDateBefore(LocalDate before);
}
