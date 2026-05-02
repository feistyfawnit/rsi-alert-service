package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.Instrument;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.InstrumentRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class OilOpportunityReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Duration TRACK_MATCH_WINDOW = Duration.ofHours(2);

    private final CandleHistoryRepository candleHistoryRepository;
    private final PositionOutcomeRepository positionOutcomeRepository;
    private final InstrumentRepository instrumentRepository;
    private final RsiCalculator rsiCalculator;

    @Value("${pnl.oil-review.path:./reports/oil-opportunity-review.md}")
    private String reportPath;

    @Value("${pnl.oil-review.lookback-days:7}")
    private int lookbackDays;

    @Value("${pnl.oil-review.rsi-threshold:40}")
    private double rsiThreshold;

    @Value("${pnl.oil-review.min-4h-rebound-pct:1.0}")
    private double minRebound4hPct;

    @Value("${pnl.oil-review.min-separation-minutes:120}")
    private int minSeparationMinutes;

    @Scheduled(cron = "0 5 6 * * *", zone = "UTC")
    public void writeScheduledReport() {
        try {
            String report = generateReport();
            Path path = Path.of(reportPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, report);
            log.info("Oil opportunity report written to {}", reportPath);
        } catch (IOException e) {
            log.error("Failed to write oil opportunity report: {}", e.getMessage());
        }
    }

    public String generateReport() {
        String now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
        StringBuilder md = new StringBuilder();
        md.append("# Oil Opportunity Review\n\n");
        md.append("*Auto-generated: ").append(now).append("*\n\n");

        Instrument oil = resolveOilInstrument();
        if (oil == null) {
            md.append("No oil instrument is configured in the instrument table.\n");
            return md.toString();
        }

        Instant nowI = Instant.now();
        Instant from = nowI.minus(Duration.ofDays(lookbackDays));
        Instant queryFrom = from.minus(Duration.ofHours(6));

        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        oil.getSymbol(), "15m", queryFrom, nowI);

        List<PositionOutcome> oilLongPositions = positionOutcomeRepository.findAll().stream()
                .filter(p -> oil.getSymbol().equals(p.getSymbol()))
                .filter(p -> Boolean.TRUE.equals(p.getIsLong()))
                .sorted(Comparator.comparing(PositionOutcome::getEntryTime))
                .toList();

        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| Instrument | **").append(oil.getName()).append("** (`").append(oil.getSymbol()).append("`) |\n");
        md.append("| Enabled | **").append(Boolean.TRUE.equals(oil.getEnabled()) ? "yes" : "no").append("** |\n");
        md.append("| Trend buy dip enabled | **").append(Boolean.TRUE.equals(oil.getTrendBuyDipEnabled()) ? "yes" : "no").append("** |\n");
        md.append("| Trend buy dip notify | **").append(Boolean.TRUE.equals(oil.getTrendBuyDipNotify()) ? "yes" : "no").append("** |\n");
        md.append("| Timeframes | `").append(oil.getTimeframes()).append("` |\n");
        md.append("| Lookback window | **").append(lookbackDays).append(" days** |\n");
        md.append("| Recorded oil long positions | **").append(oilLongPositions.size()).append("** |\n");

        if (candles.size() < 20) {
            md.append("| 15m candles available | **").append(candles.size()).append("** |\n\n");
            md.append("Not enough 15m oil candle history is available yet to review rebound opportunities.\n");
            return md.toString();
        }

        List<Opportunity> opportunities = findOpportunities(candles, from, oilLongPositions);
        long missedCount = opportunities.stream().filter(o -> !o.tracked()).count();
        long trackedCount = opportunities.size() - missedCount;

        md.append("| 15m candles analysed | **").append(candles.stream().filter(c -> !c.getCandleTime().isBefore(from)).count()).append("** |\n");
        md.append("| Rebound candidates | **").append(opportunities.size()).append("** |\n");
        md.append("| Missed candidates | **").append(missedCount).append("** |\n");
        md.append("| Matched to tracked longs | **").append(trackedCount).append("** |\n\n");

        md.append("A candidate is a 15m local low with RSI at or below ")
                .append(String.format(Locale.ROOT, "%.0f", rsiThreshold))
                .append(" that rebounds by at least ")
                .append(String.format(Locale.ROOT, "%.1f", minRebound4hPct))
                .append("% within 4 hours using stored candle data only.\n\n");

        if (opportunities.isEmpty()) {
            md.append("No oil rebound candidates met the current retrospective filter in the selected window.\n");
            return md.toString();
        }

        md.append("## Rebound Candidates\n\n");
        md.append("| Entry | Price | RSI15 | 1h max | 4h max | 24h max | 24h dd | Status |\n");
        md.append("|-------|-------|-------|--------|--------|---------|--------|--------|\n");
        opportunities.stream()
                .sorted(Comparator.comparing(Opportunity::entryTime).reversed())
                .limit(12)
                .forEach(o -> md.append("| ")
                        .append(o.entryTime().atZone(ZoneOffset.UTC).format(FMT))
                        .append(" | ")
                        .append(o.entryPrice().toPlainString())
                        .append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", o.rsi15().doubleValue()))
                        .append(" | ")
                        .append(formatPct(o.maxGain1hPct()))
                        .append(" | ")
                        .append(formatPct(o.maxGain4hPct()))
                        .append(" | ")
                        .append(formatPct(o.maxGain24hPct()))
                        .append(" | ")
                        .append(formatPct(o.maxDrawdown24hPct()))
                        .append(" | ")
                        .append(o.tracked() ? "TRACKED" : "MISSED")
                        .append(" |\n"));

        if (!oilLongPositions.isEmpty()) {
            md.append("\n## Recorded Oil Longs\n\n");
            md.append("| Entry | Signal | Exit | P&L% |\n");
            md.append("|-------|--------|------|------|\n");
            oilLongPositions.stream()
                    .sorted(Comparator.comparing(PositionOutcome::getEntryTime).reversed())
                    .limit(12)
                    .forEach(p -> md.append("| ")
                            .append(p.getEntryTime().atZone(ZoneOffset.UTC).format(FMT))
                            .append(" | ")
                            .append(p.getSignalType())
                            .append(" | ")
                            .append(p.getExitTime() != null ? p.getExitTime().atZone(ZoneOffset.UTC).format(FMT) : "OPEN")
                            .append(" | ")
                            .append(p.getPnlPct() != null ? String.format(Locale.ROOT, "%+.2f%%", p.getPnlPct().doubleValue()) : "—")
                            .append(" |\n"));
        }

        md.append("\n---\n*This review is retrospective and uses existing candle history only. It does not make extra IG API calls.*\n");
        return md.toString();
    }

    private List<Opportunity> findOpportunities(List<CandleHistory> candles, Instant from, List<PositionOutcome> oilLongPositions) {
        List<BigDecimal> closes = candles.stream().map(CandleHistory::getClose).toList();
        List<BigDecimal> rsiValues = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            if (i < 14) {
                rsiValues.add(null);
            } else {
                rsiValues.add(rsiCalculator.calculateRsi(closes.subList(0, i + 1), 14));
            }
        }

        List<Opportunity> opportunities = new ArrayList<>();
        Instant lastCandidate = null;

        for (int i = 2; i < candles.size() - 1; i++) {
            CandleHistory current = candles.get(i);
            if (current.getCandleTime().isBefore(from)) {
                continue;
            }
            BigDecimal rsi = rsiValues.get(i);
            if (rsi == null || rsi.doubleValue() > rsiThreshold) {
                continue;
            }
            if (lastCandidate != null && current.getCandleTime().isBefore(lastCandidate.plus(Duration.ofMinutes(minSeparationMinutes)))) {
                continue;
            }
            if (!isLocalLow(candles, i)) {
                continue;
            }

            Opportunity opportunity = evaluateOpportunity(candles, i, rsi, oilLongPositions);
            if (opportunity.maxGain4hPct() < minRebound4hPct) {
                continue;
            }
            opportunities.add(opportunity);
            lastCandidate = current.getCandleTime();
        }

        return opportunities;
    }

    private Opportunity evaluateOpportunity(List<CandleHistory> candles, int index, BigDecimal rsi, List<PositionOutcome> oilLongPositions) {
        CandleHistory entry = candles.get(index);
        Instant entryTime = entry.getCandleTime();
        BigDecimal entryPrice = entry.getClose();
        double maxGain1hPct = forwardExtremePct(candles, index, Duration.ofHours(1), true);
        double maxGain4hPct = forwardExtremePct(candles, index, Duration.ofHours(4), true);
        double maxGain24hPct = forwardExtremePct(candles, index, Duration.ofHours(24), true);
        double maxDrawdown24hPct = forwardExtremePct(candles, index, Duration.ofHours(24), false);
        boolean tracked = oilLongPositions.stream().anyMatch(p -> withinWindow(p.getEntryTime(), entryTime, TRACK_MATCH_WINDOW));
        return new Opportunity(entryTime, entryPrice, rsi, maxGain1hPct, maxGain4hPct, maxGain24hPct, maxDrawdown24hPct, tracked);
    }

    private boolean withinWindow(Instant positionTime, Instant candidateTime, Duration window) {
        return !positionTime.isBefore(candidateTime.minus(window)) && !positionTime.isAfter(candidateTime.plus(window));
    }

    private double forwardExtremePct(List<CandleHistory> candles, int index, Duration horizon, boolean favorable) {
        CandleHistory entry = candles.get(index);
        BigDecimal entryPrice = entry.getClose();
        Instant cutoff = entry.getCandleTime().plus(horizon);
        BigDecimal extreme = entryPrice;

        for (int i = index + 1; i < candles.size(); i++) {
            CandleHistory candle = candles.get(i);
            if (candle.getCandleTime().isAfter(cutoff)) {
                break;
            }
            BigDecimal candidate = favorable ? candle.getHigh() : candle.getLow();
            if (candidate == null) {
                continue;
            }
            if (favorable && candidate.compareTo(extreme) > 0) {
                extreme = candidate;
            }
            if (!favorable && candidate.compareTo(extreme) < 0) {
                extreme = candidate;
            }
        }

        double pct = extreme.subtract(entryPrice)
                .divide(entryPrice, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        return pct;
    }

    private boolean isLocalLow(List<CandleHistory> candles, int index) {
        BigDecimal current = candles.get(index).getClose();
        BigDecimal prev1 = candles.get(index - 1).getClose();
        BigDecimal prev2 = candles.get(index - 2).getClose();
        BigDecimal next = candles.get(index + 1).getClose();
        return current.compareTo(prev1) <= 0
                && current.compareTo(prev2) <= 0
                && next.compareTo(current) > 0;
    }

    private static final List<String> PREFERRED_OIL_SYMBOLS = List.of(
            "CC.D.LCO.USS.IP",   // Current Brent cash epic
            "CS.D.OIL.CFD.IP"    // Legacy oil epic (fallback)
    );

    private Instrument resolveOilInstrument() {
        List<Instrument> all = instrumentRepository.findAll();

        // 1. Exact match on preferred current epic (prevents stale-row confusion)
        for (String preferred : PREFERRED_OIL_SYMBOLS) {
            Instrument exact = all.stream()
                    .filter(i -> preferred.equalsIgnoreCase(i.getSymbol()))
                    .filter(i -> Boolean.TRUE.equals(i.getEnabled()))
                    .findFirst()
                    .orElse(null);
            if (exact != null) {
                return exact;
            }
        }

        // 2. Fuzzy fallback for any enabled oil-like commodity
        return all.stream()
                .filter(instrument -> instrument.getType() == Instrument.InstrumentType.COMMODITY)
                .filter(instrument -> Boolean.TRUE.equals(instrument.getEnabled()))
                .filter(instrument -> {
                    String name = instrument.getName() != null ? instrument.getName().toLowerCase(Locale.ROOT) : "";
                    String symbol = instrument.getSymbol() != null ? instrument.getSymbol().toUpperCase(Locale.ROOT) : "";
                    return name.contains("oil") || symbol.contains(".LCO.") || symbol.contains(".OIL.");
                })
                .findFirst()
                .orElse(null);
    }

    private String formatPct(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value);
    }

    private record Opportunity(
            Instant entryTime,
            BigDecimal entryPrice,
            BigDecimal rsi15,
            double maxGain1hPct,
            double maxGain4hPct,
            double maxGain24hPct,
            double maxDrawdown24hPct,
            boolean tracked
    ) {
    }
}
