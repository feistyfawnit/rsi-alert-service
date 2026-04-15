package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.DailyPriceSummary;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.DailyPriceSummaryRepository;
import com.trading.rsi.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DailyPriceRollupService {

    private static final String CSV_HEADER =
            "symbol,instrument_name,date,open,high,low,close,volume,candle_count";

    private final InstrumentRepository instrumentRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final DailyPriceSummaryRepository dailyPriceSummaryRepository;

    @Value("${archive.dir:./signal_archive}")
    private String archiveDir;

    @Value("${archive.daily-price-retention-days:${archive.retention-days:90}}")
    private int retentionDays;

    /**
     * Runs at 00:05 UTC daily.
     * 1. Rolls up yesterday's candles into daily_price_summary DB rows
     * 2. Appends the new rows to daily_prices_YYYY.csv on disk
     * 3. Purges DB rows older than retention-days (CSV is the permanent archive)
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void rollupPreviousDay() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        int created = rollupDate(yesterday);
        if (created > 0) {
            exportToCsv(yesterday);
        }
        purgeOldDbRows();
    }

    public int rollupDate(LocalDate date) {
        List<Instrument> instruments = instrumentRepository.findByEnabledTrue();
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        int created = 0;
        for (Instrument instrument : instruments) {
            try {
                if (dailyPriceSummaryRepository.findBySymbolAndSummaryDate(
                        instrument.getSymbol(), date).isPresent()) {
                    continue; // already rolled up
                }

                // Use shortest timeframe for best granularity
                String bestTf = pickShortestTimeframe(instrument.getTimeframes());
                List<CandleHistory> candles = candleHistoryRepository
                        .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                instrument.getSymbol(), bestTf, dayStart, dayEnd);

                if (candles.isEmpty()) {
                    log.debug("No candles for {} {} on {} — skipping rollup", instrument.getSymbol(), bestTf, date);
                    continue;
                }

                BigDecimal open = candles.get(0).getOpen();
                BigDecimal close = candles.get(candles.size() - 1).getClose();
                BigDecimal high = candles.stream().map(CandleHistory::getHigh).reduce(BigDecimal::max).orElse(open);
                BigDecimal low = candles.stream().map(CandleHistory::getLow).reduce(BigDecimal::min).orElse(open);
                BigDecimal volume = candles.stream()
                        .map(c -> c.getVolume() != null ? c.getVolume() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                DailyPriceSummary summary = DailyPriceSummary.builder()
                        .symbol(instrument.getSymbol())
                        .instrumentName(instrument.getName())
                        .summaryDate(date)
                        .openPrice(open)
                        .highPrice(high)
                        .lowPrice(low)
                        .closePrice(close)
                        .volume(volume)
                        .candleCount(candles.size())
                        .build();

                dailyPriceSummaryRepository.save(summary);
                created++;
                log.info("Daily rollup {} {} — O={} H={} L={} C={} ({} candles)",
                        instrument.getSymbol(), date, open, high, low, close, candles.size());

            } catch (Exception e) {
                log.warn("Failed to roll up {} for {}: {}", instrument.getSymbol(), date, e.getMessage());
            }
        }

        if (created > 0) {
            log.info("Daily price rollup complete for {}: {} instrument(s) summarised", date, created);
            exportToCsv(date);
        }
        return created;
    }

    /**
     * Appends daily summaries for the given date to daily_prices_YYYY.csv.
     * Creates the file with a header if it doesn't exist.
     */
    private void exportToCsv(LocalDate date) {
        Path dir = Path.of(archiveDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create archive dir '{}': {}", archiveDir, e.getMessage());
            return;
        }

        Path csvFile = dir.resolve("daily_prices_" + date.getYear() + ".csv");
        List<DailyPriceSummary> rows = dailyPriceSummaryRepository
                .findBySummaryDateOrderBySymbolAsc(date);

        if (rows.isEmpty()) return;

        try {
            boolean fileExists = Files.exists(csvFile);
            try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8,
                    fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
                if (!fileExists) {
                    writer.write(CSV_HEADER);
                    writer.newLine();
                }
                for (DailyPriceSummary row : rows) {
                    writer.write(toCsvRow(row));
                    writer.newLine();
                }
            }
            log.info("Daily prices exported: {} rows to {}", rows.size(), csvFile.getFileName());
        } catch (IOException e) {
            log.error("Failed to write daily prices CSV '{}': {}", csvFile, e.getMessage());
        }
    }

    /**
     * Purges daily_price_summary rows older than retention-days from the DB.
     * The CSV on disk is the permanent archive.
     */
    @Transactional
    public void purgeOldDbRows() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        List<DailyPriceSummary> old = dailyPriceSummaryRepository
                .findBySummaryDateBeforeOrderBySymbolAscSummaryDateAsc(cutoff);
        if (!old.isEmpty()) {
            dailyPriceSummaryRepository.deleteBySummaryDateBefore(cutoff);
            log.info("Purged {} daily_price_summary rows older than {} (retention: {} days)",
                    old.size(), cutoff, retentionDays);
        }
    }

    private String toCsvRow(DailyPriceSummary row) {
        return String.join(",",
                row.getSymbol(),
                row.getInstrumentName() != null ? row.getInstrumentName() : "",
                row.getSummaryDate().toString(),
                row.getOpenPrice().toPlainString(),
                row.getHighPrice().toPlainString(),
                row.getLowPrice().toPlainString(),
                row.getClosePrice().toPlainString(),
                row.getVolume() != null ? row.getVolume().toPlainString() : "",
                row.getCandleCount() != null ? row.getCandleCount().toString() : "");
    }

    private String pickShortestTimeframe(String timeframes) {
        String shortest = null;
        int shortestMinutes = Integer.MAX_VALUE;
        for (String tf : timeframes.split(",")) {
            int mins = timeframeToMinutes(tf.trim());
            if (mins < shortestMinutes) {
                shortestMinutes = mins;
                shortest = tf.trim();
            }
        }
        return shortest;
    }

    private int timeframeToMinutes(String tf) {
        return switch (tf.toLowerCase()) {
            case "1m" -> 1;
            case "3m" -> 3;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "2h" -> 120;
            case "4h" -> 240;
            case "1d" -> 1440;
            default -> 9999;
        };
    }
}
