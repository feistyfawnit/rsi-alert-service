package com.trading.rsi.service;

import com.trading.rsi.domain.PositionOutcome;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a human-readable markdown P&L report from position_outcomes.
 * Writes to disk daily (06:00 UTC) and available on-demand via API.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionReportService {

    private final PositionOutcomeRepository positionOutcomeRepository;

    @Value("${pnl.report.path:./reports/pnl-report.md}")
    private String reportPath;

    @Value("${rsi.demo.account-balance:10000}")
    private int demoAccountBalance;

    @Value("${rsi.demo.risk-percent:1}")
    private int demoRiskPercent;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Write report to disk daily at 06:00 UTC.
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "UTC")
    public void writeScheduledReport() {
        try {
            String report = generateReport();
            Path path = Path.of(reportPath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
            log.info("P&L report written to {}", reportPath);
        } catch (IOException e) {
            log.error("Failed to write P&L report: {}", e.getMessage());
        }
    }

    /**
     * Generate the full markdown report (also used by API endpoint).
     */
    public String generateReport() {
        List<PositionOutcome> all = positionOutcomeRepository.findAll();
        List<PositionOutcome> open = all.stream().filter(p -> p.getExitTime() == null).toList();
        List<PositionOutcome> closed = all.stream().filter(p -> p.getExitTime() != null).toList();

        StringBuilder md = new StringBuilder();
        String now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));

        md.append("# P&L Report\n\n");
        md.append("*Auto-generated: ").append(now).append("*\n\n");

        // ── Summary ──
        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n|--------|-------|\n");
        md.append("| Open positions | **").append(open.size()).append("** |\n");
        md.append("| Closed positions | **").append(closed.size()).append("** |\n");

        if (!closed.isEmpty()) {
            long wins = closed.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
            long tpHits = closed.stream().filter(p -> Boolean.TRUE.equals(p.getTpHit())).count();
            long slHits = closed.stream().filter(p -> Boolean.TRUE.equals(p.getSlHit())).count();
            double avgPnl = closed.stream().mapToDouble(p -> p.getPnlPct().doubleValue()).average().orElse(0);
            double winRate = (double) wins / closed.size() * 100;
            double riskEur = demoAccountBalance * demoRiskPercent / 100.0;
            double eurWins = wins * riskEur * 2;          // 2:1 R:R default
            double eurLosses = (closed.size() - wins) * riskEur;
            double netEur = eurWins - eurLosses;

            md.append("| Win rate | **").append(String.format("%.0f%%", winRate))
              .append("** (").append(wins).append("/").append(closed.size()).append(") |\n");
            md.append("| Avg P&L | **").append(String.format("%+.2f%%", avgPnl)).append("** |\n");
            md.append("| TP hits | ").append(tpHits).append(" |\n");
            md.append("| SL hits | ").append(slHits).append(" |\n");
            md.append("| Auto-closes (24h) | ").append(closed.size() - tpHits - slHits).append(" |\n");
            md.append("| Risk per trade | €").append(String.format("%.0f", riskEur)).append(" |\n");
            md.append("| Gross wins (2:1) | **+€").append(String.format("%.0f", eurWins)).append("** |\n");
            md.append("| Gross losses | **-€").append(String.format("%.0f", eurLosses)).append("** |\n");
            md.append("| **Net P&L (est.)** | **").append(netEur >= 0 ? "+" : "").append(String.format("€%.0f", netEur)).append("** |\n");
        } else {
            md.append("\n*No closed positions yet — results appear after signals fire and 24h elapses.*\n");
        }

        // ── By Instrument ──
        if (!closed.isEmpty()) {
            md.append("\n## By Instrument\n\n");
            md.append("| Instrument | N | Wins | Win% | Avg P&L | Net €(est) |\n");
            md.append("|-----------|---|------|------|---------|-----------|\n");
            double riskEurInst = demoAccountBalance * demoRiskPercent / 100.0;
            closed.stream()
                    .collect(Collectors.groupingBy(PositionOutcome::getSymbol))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        List<PositionOutcome> group = entry.getValue();
                        long w = group.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
                        double wr = (double) w / group.size() * 100;
                        double avg = group.stream().mapToDouble(p -> p.getPnlPct().doubleValue()).average().orElse(0);
                        double net = (w * riskEurInst * 2) - ((group.size() - w) * riskEurInst);
                        md.append("| ").append(entry.getKey())
                          .append(" | ").append(group.size())
                          .append(" | ").append(w)
                          .append(" | ").append(String.format("%.0f%%", wr))
                          .append(" | ").append(String.format("%+.2f%%", avg))
                          .append(" | ").append(net >= 0 ? "+" : "").append(String.format("€%.0f", net))
                          .append(" |\n");
                    });
        }

        // ── By Signal Type ──
        if (!closed.isEmpty()) {
            md.append("\n## By Signal Type\n\n");
            md.append("| Type | N | Wins | Win% | Avg P&L | TP | SL | Verdict |\n");
            md.append("|------|---|------|------|---------|----|----|--------|\n");

            closed.stream()
                    .collect(Collectors.groupingBy(p -> p.getSignalType().name()))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        List<PositionOutcome> group = entry.getValue();
                        long w = group.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
                        long tp = group.stream().filter(p -> Boolean.TRUE.equals(p.getTpHit())).count();
                        long sl = group.stream().filter(p -> Boolean.TRUE.equals(p.getSlHit())).count();
                        double wr = (double) w / group.size() * 100;
                        double avg = group.stream().mapToDouble(p -> p.getPnlPct().doubleValue()).average().orElse(0);
                        String verdict = avg > 0.5 ? "✅" : avg < -0.5 ? "❌" : "➖";
                        md.append("| ").append(entry.getKey())
                          .append(" | ").append(group.size())
                          .append(" | ").append(w)
                          .append(" | ").append(String.format("%.0f%%", wr))
                          .append(" | ").append(String.format("%+.2f%%", avg))
                          .append(" | ").append(tp)
                          .append(" | ").append(sl)
                          .append(" | ").append(verdict)
                          .append(" |\n");
                    });
        }

        // ── Open Positions ──
        if (!open.isEmpty()) {
            md.append("\n## Open Positions\n\n");
            md.append("| Entry Time (UTC) | Symbol | Dir | Signal | Entry | TP | SL | Trend | RSI f·m·s | Stoch K/D |\n");
            md.append("|------------------|--------|-----|--------|-------|----|----|-------|-----------|-----------|\n");
            open.stream()
                    .sorted(Comparator.comparing(PositionOutcome::getEntryTime).reversed())
                    .forEach(p -> {
                        String dir = Boolean.TRUE.equals(p.getIsLong()) ? "▲" : "▼";
                        String trend = p.getTrendState() != null ? switch (p.getTrendState()) {
                            case "STRONG_UPTREND" -> "📈BULL";
                            case "STRONG_DOWNTREND" -> "📉BEAR";
                            default -> "—";
                        } : "—";
                        String rsi = (p.getRsiFast() != null)
                                ? String.format("%.0f·%.0f·%.0f",
                                    p.getRsiFast().doubleValue(),
                                    p.getRsiMid() != null ? p.getRsiMid().doubleValue() : 0,
                                    p.getRsiSlow() != null ? p.getRsiSlow().doubleValue() : 0)
                                : "—";
                        String stoch = (p.getStochK() != null)
                                ? String.format("%.0f/%.0f", p.getStochK().doubleValue(),
                                    p.getStochD() != null ? p.getStochD().doubleValue() : 0)
                                : "—";
                        md.append("| ").append(p.getEntryTime().atZone(ZoneOffset.UTC).format(FMT))
                          .append(" | ").append(p.getSymbol())
                          .append(" | ").append(dir)
                          .append(" | ").append(p.getSignalType())
                          .append(" | ").append(p.getEntryPrice().toPlainString())
                          .append(" | ").append(p.getTpPrice().toPlainString())
                          .append(" | ").append(p.getSlPrice().toPlainString())
                          .append(" | ").append(trend)
                          .append(" | ").append(rsi)
                          .append(" | ").append(stoch)
                          .append(" |\n");
                    });
        }

        // ── By Day ──
        if (!closed.isEmpty()) {
            double riskEurDay = demoAccountBalance * demoRiskPercent / 100.0;
            md.append("\n## By Day\n\n");
            md.append("| Date (UTC) | Signals | Closed | Wins | Losses | 24h | Net €(est) |\n");
            md.append("|-----------|---------|--------|------|--------|-----|------------|\n");

            long[] totSignals = {0}, totClosed = {0}, totWins = {0}, totLosses = {0}, totExpired = {0};
            double[] totNet = {0};

            all.stream()
                    .collect(Collectors.groupingBy(p ->
                            p.getEntryTime().atZone(ZoneOffset.UTC).toLocalDate().toString()))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        List<PositionOutcome> day = entry.getValue();
                        long dayClosed  = day.stream().filter(p -> p.getExitTime() != null).count();
                        long dayWins    = day.stream().filter(p -> p.getExitTime() != null
                                && p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
                        long dayLosses  = day.stream().filter(p -> p.getExitTime() != null
                                && Boolean.TRUE.equals(p.getSlHit())).count();
                        long dayExpired = dayClosed - dayWins - dayLosses;
                        double dayNet   = (dayWins * riskEurDay * 2) - (dayLosses * riskEurDay);
                        totSignals[0] += day.size(); totClosed[0] += dayClosed;
                        totWins[0] += dayWins; totLosses[0] += dayLosses;
                        totExpired[0] += dayExpired; totNet[0] += dayNet;
                        String netStr = dayClosed == 0 ? "—"
                                : (dayNet >= 0 ? "+" : "") + String.format("€%.0f", dayNet);
                        md.append("| ").append(entry.getKey())
                          .append(" | ").append(day.size())
                          .append(" | ").append(dayClosed)
                          .append(" | ").append(dayWins)
                          .append(" | ").append(dayLosses)
                          .append(" | ").append(dayExpired)
                          .append(" | ").append(netStr)
                          .append(" |\n");
                    });

            md.append("| **Total** | **").append(totSignals[0])
              .append("** | **").append(totClosed[0])
              .append("** | **").append(totWins[0])
              .append("** | **").append(totLosses[0])
              .append("** | **").append(totExpired[0])
              .append("** | **").append(totNet[0] >= 0 ? "+" : "").append(String.format("€%.0f", totNet[0]))
              .append("** |\n");
        }

        // ── All Closed Positions ──
        if (!closed.isEmpty()) {
            double riskEurExit = demoAccountBalance * demoRiskPercent / 100.0;
            md.append("\n## All Closed Positions (").append(closed.size()).append(" total)\n\n");
            md.append("| Entry Time (UTC) | Exit Time (UTC) | Symbol | Dir | Signal | P&L% | Est € | Result | Held |\n");
            md.append("|------------------|-----------------|--------|-----|--------|------|-------|--------|------|\n");
            closed.stream()
                    .sorted(Comparator.comparing(PositionOutcome::getEntryTime).reversed())
                    .forEach(p -> {
                        boolean win = Boolean.TRUE.equals(p.getTpHit());
                        boolean loss = Boolean.TRUE.equals(p.getSlHit());
                        String result = win ? "TP ✅" : loss ? "SL ❌" : "24h ➖";
                        double estEur = win ? riskEurExit * 2 : -riskEurExit;
                        String dir = Boolean.TRUE.equals(p.getIsLong()) ? "▲" : "▼";
                        md.append("| ").append(p.getEntryTime().atZone(ZoneOffset.UTC).format(FMT))
                          .append(" | ").append(p.getExitTime().atZone(ZoneOffset.UTC).format(FMT))
                          .append(" | ").append(p.getSymbol())
                          .append(" | ").append(dir)
                          .append(" | ").append(p.getSignalType())
                          .append(" | ").append(String.format("%+.2f%%", p.getPnlPct().doubleValue()))
                          .append(" | ").append(estEur >= 0 ? "+" : "").append(String.format("€%.0f", estEur))
                          .append(" | ").append(result)
                          .append(" | ").append(p.getHoldingHours() != null ? String.format("%.1fh", p.getHoldingHours()) : "?")
                          .append(" |\n");
                    });
        }

        md.append("\n---\n*Run `make pnl-report` to refresh. Auto-updates daily at 06:00 UTC.*\n");
        return md.toString();
    }

    /**
     * Generate CSV of all closed positions for download.
     */
    public String generateCsv() {
        List<PositionOutcome> closed = positionOutcomeRepository.findByExitTimeIsNotNull();
        List<PositionOutcome> open   = positionOutcomeRepository.findByExitTimeIsNull();
        List<PositionOutcome> all = new java.util.ArrayList<>();
        all.addAll(open);
        all.addAll(closed);
        all.sort(Comparator.comparing(PositionOutcome::getEntryTime).reversed());

        double riskEur = demoAccountBalance * demoRiskPercent / 100.0;
        StringBuilder csv = new StringBuilder();
        csv.append("id,direction,signal_type,symbol,entry_time,entry_price,stop_price,target_price,");
        csv.append("result,exit_time,pnl_pct,est_eur_pnl,holding_hrs\n");

        for (PositionOutcome p : all) {
            String direction = Boolean.TRUE.equals(p.getIsLong()) ? "LONG" : "SHORT";
            String result = p.getExitTime() == null ? "OPEN"
                    : Boolean.TRUE.equals(p.getTpHit()) ? "WIN"
                    : Boolean.TRUE.equals(p.getSlHit()) ? "LOSS" : "EXPIRED";
            double estEur = 0;
            if (p.getPnlPct() != null && p.getExitTime() != null) {
                estEur = "WIN".equals(result) ? riskEur * 2 : -riskEur;
            }
            csv.append(p.getId()).append(",")
               .append(direction).append(",")
               .append(p.getSignalType()).append(",")
               .append(p.getSymbol()).append(",")
               .append(p.getEntryTime().atZone(ZoneOffset.UTC).format(FMT)).append(",")
               .append(p.getEntryPrice().toPlainString()).append(",")
               .append(p.getSlPrice().toPlainString()).append(",")
               .append(p.getTpPrice().toPlainString()).append(",")
               .append(result).append(",")
               .append(p.getExitTime() != null ? p.getExitTime().atZone(ZoneOffset.UTC).format(FMT) : "").append(",")
               .append(p.getPnlPct() != null ? String.format("%+.2f", p.getPnlPct().doubleValue()) : "").append(",")
               .append(p.getExitTime() != null ? String.format("%+.0f", estEur) : "").append(",")
               .append(p.getHoldingHours() != null ? String.format("%.1f", p.getHoldingHours()) : "")
               .append("\n");
        }
        return csv.toString();
    }
}
