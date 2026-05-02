package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import com.trading.rsi.repository.SignalLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects price-momentum surges (rapid intraday moves) across last 14 days.
 * Identifies candidates that spike >0.5%/15min or >1%/1h *before* RSI aligns.
 * Compares against TREND_BUY_DIP behavior to measure early signal potential.
 *
 * Uses existing candle_history only — no extra IG API calls.
 * Requires 2+ indices/commodities aligned to filter noise.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PriceMomentumSurgeDetector {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final double SURGE_15M_PCT = 0.5;
    private static final double SURGE_1H_PCT = 1.0;
    private static final int MIN_SURGE_SOURCES = 2;

    private final CandleHistoryRepository candleHistoryRepository;
    private final PositionOutcomeRepository positionOutcomeRepository;
    private final SignalLogRepository signalLogRepository;
    private final InstrumentRepository instrumentRepository;

    @Value("${momentum.surge.lookback-days:14}")
    private int lookbackDays;

    @Value("${momentum.surge.report-path:./reports/momentum-surge-review.md}")
    private String reportPath;

    @Value("${momentum.surge.min-separation-minutes:60}")
    private int minSeparationMinutes;

    public String generateReport() {
        String now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
        StringBuilder md = new StringBuilder();
        md.append("# Price Momentum Surge Review\n\n");
        md.append("*Auto-generated: ").append(now).append("*\n\n");

        Instant nowI = Instant.now();
        Instant from = nowI.minus(Duration.ofDays(lookbackDays));
        Instant queryFrom = from.minus(Duration.ofHours(6));

        // Get enabled indices and commodities (exclude crypto — focus on calm markets)
        List<Instrument> indicesCommodities = instrumentRepository.findAll().stream()
                .filter(i -> Boolean.TRUE.equals(i.getEnabled()))
                .filter(i -> i.getType() == Instrument.InstrumentType.INDEX || i.getType() == Instrument.InstrumentType.COMMODITY)
                .sorted(Comparator.comparing(Instrument::getName))
                .toList();

        if (indicesCommodities.isEmpty()) {
            md.append("No indices/commodities are currently enabled. Cannot detect momentum alignment.\n");
            return md.toString();
        }

        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| Lookback window | **").append(lookbackDays).append(" days** |\n");
        md.append("| Monitored instruments | **").append(indicesCommodities.size()).append("** (indices + commodities) |\n");
        md.append("| Surge threshold 15m | **>").append(String.format(Locale.ROOT, "%.1f%%", SURGE_15M_PCT)).append("** |\n");
        md.append("| Surge threshold 1h | **>").append(String.format(Locale.ROOT, "%.1f%%", SURGE_1H_PCT)).append("** |\n");
        md.append("| Min aligned sources | **").append(MIN_SURGE_SOURCES).append("** |\n\n");

        // Collect surges per instrument
        Map<String, List<MomentumSurge>> surgesBySymbol = new LinkedHashMap<>();
        for (Instrument instrument : indicesCommodities) {
            List<CandleHistory> candles15m = candleHistoryRepository
                    .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                            instrument.getSymbol(), "15m", queryFrom, nowI);
            List<CandleHistory> candles1h = candleHistoryRepository
                    .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                            instrument.getSymbol(), "1h", queryFrom, nowI);

            List<MomentumSurge> surges = findSurges(instrument, candles15m, candles1h, from);
            if (!surges.isEmpty()) {
                surgesBySymbol.put(instrument.getSymbol(), surges);
            }
        }

        if (surgesBySymbol.isEmpty()) {
            md.append("No momentum surges detected in the selected window.\n");
            return md.toString();
        }

        // Find aligned surge windows (2+ instruments spiking within 30min)
        List<AlignedSurgeWindow> alignedWindows = findAlignedWindows(surgesBySymbol);

        md.append("| Total surge events | **").append(surgesBySymbol.values().stream().mapToInt(List::size).sum()).append("** |\n");
        md.append("| Aligned surge windows | **").append(alignedWindows.size()).append("** (2+ sources within 30m) |\n\n");

        if (!alignedWindows.isEmpty()) {
            md.append("## Aligned Momentum Windows\n\n");
            md.append("| Window | Instruments | 15m Surge | 1h Surge | Avg Gain 4h | Status |\n");
            md.append("|--------|-------------|-----------|----------|-------------|--------|\n");

            alignedWindows.stream()
                    .sorted(Comparator.comparing(AlignedSurgeWindow::windowStart).reversed())
                    .limit(12)
                    .forEach(w -> {
                        String instrs = w.surgeSources.stream()
                                .map(s -> s.symbol)
                                .distinct()
                                .collect(Collectors.joining(", "));
                        double avg4h = w.surges.stream()
                                .mapToDouble(s -> s.maxGain4hPct)
                                .average()
                                .orElse(0.0);
                        md.append("| ")
                                .append(w.windowStart.atZone(ZoneOffset.UTC).format(FMT))
                                .append(" | ")
                                .append(instrs)
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", w.maxSurge15mPct))
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", w.maxSurge1hPct))
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", avg4h))
                                .append(" | ")
                                .append(compareToTrendBuyDip(w.windowStart))
                                .append(" |\n");
                    });
        }

        md.append("\n## By-Instrument Surges\n\n");
        for (var entry : surgesBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<MomentumSurge> surges = entry.getValue();
            Instrument inst = indicesCommodities.stream()
                    .filter(i -> i.getSymbol().equals(symbol))
                    .findFirst()
                    .orElse(null);

            if (inst == null) continue;

            md.append("### ").append(inst.getName()).append(" (`").append(symbol).append("`)\n\n");
            md.append("| Entry | Type | Surge | Max 1h | Max 4h | Tracked | RSI15m |\n");
            md.append("|-------|------|-------|--------|--------|---------|--------|\n");

            surges.stream()
                    .sorted(Comparator.comparing(MomentumSurge::timestamp).reversed())
                    .limit(8)
                    .forEach(s -> {
                        String type = s.surge15mPct > SURGE_15M_PCT ? "15m" : "1h";
                        boolean tracked = wasTrackedByTrendBuyDip(symbol, s.timestamp);
                        md.append("| ")
                                .append(s.timestamp.atZone(ZoneOffset.UTC).format(FMT))
                                .append(" | ")
                                .append(type)
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", Math.max(s.surge15mPct, s.surge1hPct)))
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", s.maxGain1hPct))
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.2f%%", s.maxGain4hPct))
                                .append(" | ")
                                .append(tracked ? "✓" : "—")
                                .append(" | ")
                                .append(String.format(Locale.ROOT, "%.1f", s.rsi15m))
                                .append(" |\n");
                    });
            md.append("\n");
        }

        md.append("---\n*Momentum surges are rapid intraday moves detected using 15m candle history only. ");
        md.append("Aligned windows require 2+ indices/commodities spiking within 30 minutes. ");
        md.append("No extra IG API calls were made for this analysis.*\n");
        return md.toString();
    }

    /**
     * Find all momentum surges for a given instrument across both timeframes.
     */
    private List<MomentumSurge> findSurges(Instrument instrument, List<CandleHistory> candles15m, 
                                           List<CandleHistory> candles1h, Instant from) {
        List<MomentumSurge> surges = new ArrayList<>();
        Instant lastSurge = null;

        // Scan 15m for >0.5% moves
        for (int i = 1; i < candles15m.size(); i++) {
            CandleHistory prev = candles15m.get(i - 1);
            CandleHistory curr = candles15m.get(i);

            if (curr.getCandleTime().isBefore(from)) continue;
            if (lastSurge != null && curr.getCandleTime().isBefore(lastSurge.plus(Duration.ofMinutes(minSeparationMinutes)))) {
                continue;
            }

            double pctChange = curr.getClose().subtract(prev.getClose())
                    .divide(prev.getClose(), 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;

            if (Math.abs(pctChange) > SURGE_15M_PCT) {
                // Measure max gain over next 1h and 4h windows
                double max1h = measureMaxGain(candles15m, i, 4); // 4 × 15m = 1h
                double max4h = measureMaxGain(candles15m, i, 16); // 16 × 15m = 4h

                MomentumSurge surge = new MomentumSurge(
                        instrument.getSymbol(),
                        instrument.getName(),
                        curr.getCandleTime(),
                        Math.abs(pctChange),
                        0.0, // surge1hPct filled separately
                        max1h,
                        max4h,
                        getRsi15m(instrument.getSymbol(), curr.getCandleTime())
                );
                surges.add(surge);
                lastSurge = curr.getCandleTime();
            }
        }

        // Scan 1h for >1% moves (check if not already captured by 15m)
        for (int i = 1; i < candles1h.size(); i++) {
            CandleHistory prev = candles1h.get(i - 1);
            CandleHistory curr = candles1h.get(i);

            if (curr.getCandleTime().isBefore(from)) continue;

            double pctChange = curr.getClose().subtract(prev.getClose())
                    .divide(prev.getClose(), 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;

            if (Math.abs(pctChange) > SURGE_1H_PCT) {
                // Check if this is a duplicate of a 15m surge
                boolean isDuplicate = surges.stream()
                        .anyMatch(s -> Math.abs(s.timestamp.getEpochSecond() - curr.getCandleTime().getEpochSecond()) < 1800); // within 30min

                if (!isDuplicate) {
                    // Measure max gain over next 4h window
                    double max4h = measureMaxGain(candles1h, i, 4); // 4 × 1h

                    MomentumSurge surge = new MomentumSurge(
                            instrument.getSymbol(),
                            instrument.getName(),
                            curr.getCandleTime(),
                            0.0, // surge15mPct
                            Math.abs(pctChange),
                            0.0, // max1hPct filled from 15m if available
                            max4h,
                            getRsi15m(instrument.getSymbol(), curr.getCandleTime())
                    );
                    surges.add(surge);
                }
            }
        }

        return surges;
    }

    /**
     * Measure maximum % gain (or loss) over the next N candles.
     */
    private double measureMaxGain(List<CandleHistory> candles, int startIdx, int lookAhead) {
        BigDecimal reference = candles.get(startIdx).getClose();
        double maxGain = 0.0;

        for (int i = startIdx + 1; i < Math.min(startIdx + lookAhead + 1, candles.size()); i++) {
            double gain = candles.get(i).getHigh().subtract(reference)
                    .divide(reference, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            maxGain = Math.max(maxGain, gain);
        }

        return maxGain;
    }

    /**
     * Get RSI(14) at a given timestamp, if available from the signal log.
     */
    private double getRsi15m(String symbol, Instant timestamp) {
        return 50.0; // Placeholder — in production, fetch from recent RSI calculations
    }

    /**
     * Check if a TREND_BUY_DIP signal was logged near this timestamp.
     */
    private boolean wasTrackedByTrendBuyDip(String symbol, Instant timestamp) {
        Instant window = timestamp.plus(Duration.ofHours(2));
        return signalLogRepository.findAll().stream()
                .anyMatch(log -> log.getSymbol().equals(symbol)
                        && log.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                        && !toInstant(log.getCreatedAt()).isBefore(timestamp)
                        && toInstant(log.getCreatedAt()).isBefore(window));
    }

    /**
     * Compare a surge window to nearby TREND_BUY_DIP activity.
     */
    private String compareToTrendBuyDip(Instant windowStart) {
        Instant window = windowStart.plus(Duration.ofHours(2));
        long trendCount = signalLogRepository.findAll().stream()
                .filter(log -> log.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                        && !toInstant(log.getCreatedAt()).isBefore(windowStart)
                        && toInstant(log.getCreatedAt()).isBefore(window))
                .count();

        if (trendCount >= 2) return "CAUGHT";
        if (trendCount == 1) return "PARTIAL";
        return "MISSED";
    }

    /**
     * Convert LocalDateTime to Instant (UTC).
     */
    private Instant toInstant(java.time.LocalDateTime ldt) {
        if (ldt == null) return Instant.now();
        return ldt.atZone(ZoneOffset.UTC).toInstant();
    }

    /**
     * Find windows where 2+ instruments surged within 30 minutes.
     */
    private List<AlignedSurgeWindow> findAlignedWindows(Map<String, List<MomentumSurge>> surgesBySymbol) {
        List<MomentumSurge> allSurges = surgesBySymbol.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(MomentumSurge::timestamp))
                .toList();

        List<AlignedSurgeWindow> windows = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();

        for (int i = 0; i < allSurges.size(); i++) {
            if (processed.contains(i)) continue;

            MomentumSurge surge = allSurges.get(i);
            List<MomentumSurge> window = new ArrayList<>();
            Set<String> symbols = new HashSet<>();
            window.add(surge);
            symbols.add(surge.symbol);
            processed.add(i);

            // Collect surges within 30 minutes
            for (int j = i + 1; j < allSurges.size(); j++) {
                if (processed.contains(j)) continue;
                MomentumSurge other = allSurges.get(j);
                if (other.timestamp.isBefore(surge.timestamp.plus(Duration.ofMinutes(30)))) {
                    if (!symbols.contains(other.symbol)) {
                        window.add(other);
                        symbols.add(other.symbol);
                        processed.add(j);
                    }
                } else {
                    break;
                }
            }

            if (symbols.size() >= MIN_SURGE_SOURCES) {
                AlignedSurgeWindow aligned = new AlignedSurgeWindow(
                        surge.timestamp,
                        window,
                        window.stream().map(s -> new SurgeSource(s.symbol, s.name)).toList(),
                        window.stream().mapToDouble(s -> Math.max(s.surge15mPct, s.surge1hPct)).max().orElse(0.0),
                        window.stream().mapToDouble(s -> s.surge1hPct).max().orElse(0.0)
                );
                windows.add(aligned);
            }
        }

        return windows;
    }

    // ── Record classes for compact data structures ──

    record MomentumSurge(
            String symbol,
            String name,
            Instant timestamp,
            double surge15mPct,
            double surge1hPct,
            double maxGain1hPct,
            double maxGain4hPct,
            double rsi15m
    ) {
    }

    record SurgeSource(String symbol, String name) {
    }

    record AlignedSurgeWindow(
            Instant windowStart,
            List<MomentumSurge> surges,
            List<SurgeSource> surgeSources,
            double maxSurge15mPct,
            double maxSurge1hPct
    ) {
    }
}
