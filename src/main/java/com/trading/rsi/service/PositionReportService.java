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
import java.time.Duration;
import java.time.Instant;
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
    private final PriceHistoryService priceHistoryService;

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
        double riskEur = demoAccountBalance * demoRiskPercent / 100.0;

        // ── Open Positions (at top, same format as closed) ──
        if (!open.isEmpty()) {
            md.append("## Open Positions (").append(open.size()).append(")\n\n");
            md.append("| Entry Time (UTC) | Exit Time (UTC) | Symbol | Dir | Signal | P&L% | Est € | Result | Held |\n");
            md.append("|------------------|-----------------|--------|-----|--------|------|-------|--------|------|\n");
            Instant nowI = Instant.now();
            open.stream()
                    .sorted(Comparator.comparing(PositionOutcome::getEntryTime).reversed())
                    .forEach(p -> md.append(formatOpenRow(p, riskEur, nowI)));
            md.append("\n");
        }

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

            // R-multiple based € estimate: 24h auto-closes at +2R count as +2R, not a fixed win/loss.
            double eurWins = closed.stream()
                    .mapToDouble(p -> Math.max(0, estEur(p, riskEur))).sum();
            double eurLosses = closed.stream()
                    .mapToDouble(p -> Math.min(0, estEur(p, riskEur))).sum();
            double netEur = eurWins + eurLosses;

            // Realistic Net — excludes (symbol, signalType) groups with ≥3 trades AND zero TP hits.
            // Proven-loser configs auto-drop; once a config hits a TP it rejoins the total.
            java.util.Set<String> badConfigs = closed.stream()
                    .collect(Collectors.groupingBy(p -> p.getSymbol() + "|" + p.getSignalType().name()))
                    .entrySet().stream()
                    .filter(e -> e.getValue().size() >= 3
                            && e.getValue().stream().noneMatch(p -> Boolean.TRUE.equals(p.getTpHit())))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            double realisticNet = closed.stream()
                    .filter(p -> !badConfigs.contains(p.getSymbol() + "|" + p.getSignalType().name()))
                    .mapToDouble(p -> estEur(p, riskEur)).sum();

            md.append("| Win rate | **").append(String.format("%.0f%%", winRate))
              .append("** (").append(wins).append("/").append(closed.size()).append(") |\n");
            md.append("| Avg P&L | **").append(String.format("%+.2f%%", avgPnl)).append("** |\n");
            md.append("| TP hits | ").append(tpHits).append(" |\n");
            md.append("| SL hits | ").append(slHits).append(" |\n");
            md.append("| Auto-closes (24h) | ").append(closed.size() - tpHits - slHits).append(" |\n");
            md.append("| Risk per trade | €").append(String.format("%.0f", riskEur)).append(" |\n");
            md.append("| Gross wins (R-weighted) | **+€").append(String.format("%.0f", eurWins)).append("** |\n");
            md.append("| Gross losses | **€").append(String.format("%.0f", eurLosses)).append("** |\n");
            md.append("| **Net P&L (est.)** | **").append(netEur >= 0 ? "+" : "").append(String.format("€%.0f", netEur)).append("** |\n");
            md.append("| **Realistic Net** (excl. 0-win configs) | **")
              .append(realisticNet >= 0 ? "+" : "").append(String.format("€%.0f", realisticNet)).append("** |\n");
            if (!badConfigs.isEmpty()) {
                md.append("| *Excluded configs* | *")
                  .append(String.join(", ", badConfigs)).append("* |\n");
            }
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
                        double net = group.stream().mapToDouble(p -> estEur(p, riskEurInst)).sum();
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
                        double dayNet   = day.stream()
                                .filter(p -> p.getExitTime() != null)
                                .mapToDouble(p -> estEur(p, riskEurDay)).sum();
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
                        double estEur = estEur(p, riskEurExit);
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
                estEur = estEur(p, riskEur);
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

    /**
     * Format an open position as a markdown row aligned with the closed-position table.
     * P&L% = unrealized (current vs entry), Est € = R-multiple, Result = "OPEN", Exit = "—".
     */
    private String formatOpenRow(PositionOutcome p, double riskEur, Instant nowI) {
        String dir = Boolean.TRUE.equals(p.getIsLong()) ? "▲" : "▼";
        BigDecimal cur = priceHistoryService.getLatestPrice(p.getSymbol());
        String pnlPctStr = "—";
        String estEurStr = "—";
        if (cur != null && p.getEntryPrice() != null) {
            double entry = p.getEntryPrice().doubleValue();
            double curD = cur.doubleValue();
            double unrealPct = (Boolean.TRUE.equals(p.getIsLong())
                    ? (curD - entry) / entry
                    : (entry - curD) / entry) * 100.0;
            pnlPctStr = String.format("%+.2f%%", unrealPct);
            if (p.getSlPrice() != null) {
                double stopPct = Math.abs(entry - p.getSlPrice().doubleValue()) / entry * 100.0;
                if (stopPct > 0) {
                    double eur = (unrealPct / stopPct) * riskEur;
                    estEurStr = (eur >= 0 ? "+" : "") + String.format("€%.0f", eur);
                }
            }
        }
        double heldHours = Duration.between(p.getEntryTime(), nowI).toMinutes() / 60.0;
        return "| " + p.getEntryTime().atZone(ZoneOffset.UTC).format(FMT)
                + " | — "
                + " | " + p.getSymbol()
                + " | " + dir
                + " | " + p.getSignalType()
                + " | " + pnlPctStr
                + " | " + estEurStr
                + " | OPEN"
                + " | " + String.format("%.1fh", heldHours)
                + " |\n";
    }

    /**
     * R-multiple € estimate: uses actual pnlPct vs. the stop distance at entry.
     *
     * R = pnlPct / stopPctAtEntry, then € = R * riskEur.
     *
     * This correctly credits 24h auto-closes at e.g. +2.8% with a 1% stop as +2.8R ≈ +€280,
     * not as a fixed-€ loss. For positions still open or missing P&L it returns 0.
     */
    private double estEur(PositionOutcome p, double riskEur) {
        if (p.getPnlPct() == null || p.getExitTime() == null
                || p.getEntryPrice() == null || p.getSlPrice() == null) return 0;
        double entry = p.getEntryPrice().doubleValue();
        if (entry <= 0) return 0;
        double stopPct = Math.abs(entry - p.getSlPrice().doubleValue()) / entry * 100.0;
        if (stopPct <= 0) return 0;
        double rMultiple = p.getPnlPct().doubleValue() / stopPct;
        return rMultiple * riskEur;
    }
}
