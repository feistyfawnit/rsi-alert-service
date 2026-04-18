package com.trading.rsi.service;

import com.trading.rsi.domain.CandleHistory;
import com.trading.rsi.domain.PositionOutcome;
import com.trading.rsi.domain.SignalLog;
import com.trading.rsi.event.SignalEvent;
import com.trading.rsi.model.RsiSignal;
import com.trading.rsi.repository.CandleHistoryRepository;
import com.trading.rsi.repository.PositionOutcomeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks position outcomes for actionable signals (OVERSOLD, OVERBOUGHT, TREND_BUY_DIP, TREND_SELL_RALLY).
 * On signal: inserts an open position with calculated TP/SL levels.
 * Hourly job: checks 1h candle highs/lows for exit conditions (TP hit, SL hit, or 24h auto-close).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionOutcomeService {

    private final PositionOutcomeRepository positionOutcomeRepository;
    private final CandleHistoryRepository candleHistoryRepository;
    private final PriceHistoryService priceHistoryService;

    private static final Duration MAX_HOLDING = Duration.ofHours(24);

    private static final Set<SignalLog.SignalType> TRACKED_SIGNALS = Set.of(
            SignalLog.SignalType.OVERSOLD,
            SignalLog.SignalType.OVERBOUGHT,
            SignalLog.SignalType.TREND_BUY_DIP,
            SignalLog.SignalType.TREND_SELL_RALLY
    );

    @Value("${rsi.demo.stop-percent-crypto:2.0}")
    private double stopPercentCrypto;

    @Value("${rsi.demo.stop-percent-index:0.5}")
    private double stopPercentIndex;

    @Value("${rsi.demo.stop-percent-commodity:1.0}")
    private double stopPercentCommodity;

    @EventListener
    @Async
    public void handleSignalEvent(SignalEvent event) {
        RsiSignal signal = event.getSignal();
        if (!TRACKED_SIGNALS.contains(signal.getSignalType())) return;

        boolean isLong = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
                || signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP;
        boolean isTrend = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
                || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;

        BigDecimal entry = signal.getCurrentPrice();
        double stopPct = inferStopPercent(signal.getSymbol());
        double effectiveStopPct = isTrend ? stopPct * 0.5 : stopPct;

        long stopPts = Math.max(
                Math.round(entry.doubleValue() * effectiveStopPct / 100.0),
                Math.max(Math.round(entry.doubleValue() * 0.002), 2)
        );
        long limitPts = isTrend ? stopPts * 3 : stopPts * 2;

        BigDecimal tpPrice;
        BigDecimal slPrice;
        if (isLong) {
            slPrice = entry.subtract(BigDecimal.valueOf(stopPts));
            tpPrice = entry.add(BigDecimal.valueOf(limitPts));
        } else {
            slPrice = entry.add(BigDecimal.valueOf(stopPts));
            tpPrice = entry.subtract(BigDecimal.valueOf(limitPts));
        }

        PositionOutcome position = PositionOutcome.builder()
                .symbol(signal.getSymbol())
                .signalType(signal.getSignalType())
                .entryPrice(entry)
                .entryTime(Instant.now())
                .tpPrice(tpPrice)
                .slPrice(slPrice)
                .isLong(isLong)
                .build();

        positionOutcomeRepository.save(position);
        log.info("Position opened: {} {} at {} — TP {} SL {} ({})",
                signal.getSymbol(), signal.getSignalType(), entry, tpPrice, slPrice, isLong ? "LONG" : "SHORT");
    }

    /**
     * Hourly job: check open positions for exit conditions using 1h candle highs/lows.
     */
    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    public void checkExitConditions() {
        List<PositionOutcome> openPositions = positionOutcomeRepository.findByExitTimeIsNull();
        if (openPositions.isEmpty()) return;

        log.info("Checking exit conditions for {} open positions", openPositions.size());
        Instant now = Instant.now();

        for (PositionOutcome pos : openPositions) {
            try {
                checkAndClosePosition(pos, now);
            } catch (Exception e) {
                log.error("Error checking position {} for {}: {}", pos.getId(), pos.getSymbol(), e.getMessage());
            }
        }
    }

    void checkAndClosePosition(PositionOutcome pos, Instant now) {
        List<CandleHistory> candles = candleHistoryRepository
                .findBySymbolAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        pos.getSymbol(), "1h", pos.getEntryTime(), now);

        boolean tpHit = false;
        boolean slHit = false;
        BigDecimal exitPrice = null;
        Instant exitTime = null;

        for (CandleHistory candle : candles) {
            if (pos.getIsLong()) {
                if (candle.getLow().compareTo(pos.getSlPrice()) <= 0) {
                    slHit = true;
                    exitPrice = pos.getSlPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
                if (candle.getHigh().compareTo(pos.getTpPrice()) >= 0) {
                    tpHit = true;
                    exitPrice = pos.getTpPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
            } else {
                if (candle.getHigh().compareTo(pos.getSlPrice()) >= 0) {
                    slHit = true;
                    exitPrice = pos.getSlPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
                if (candle.getLow().compareTo(pos.getTpPrice()) <= 0) {
                    tpHit = true;
                    exitPrice = pos.getTpPrice();
                    exitTime = candle.getCandleTime();
                    break;
                }
            }
        }

        // 24h auto-close if neither TP nor SL hit
        if (!tpHit && !slHit && pos.getEntryTime().plus(MAX_HOLDING).isBefore(now)) {
            // Use latest known price for the symbol
            BigDecimal latestPrice = priceHistoryService.getLatestPrice(pos.getSymbol());
            if (latestPrice != null) {
                exitPrice = latestPrice;
                exitTime = now;
            } else if (!candles.isEmpty()) {
                CandleHistory lastCandle = candles.get(candles.size() - 1);
                exitPrice = lastCandle.getClose();
                exitTime = lastCandle.getCandleTime();
            }
        }

        if (exitPrice != null && exitTime != null) {
            closePosition(pos, exitPrice, exitTime, tpHit, slHit);
        }
    }

    private void closePosition(PositionOutcome pos, BigDecimal exitPrice, Instant exitTime,
                                boolean tpHit, boolean slHit) {
        BigDecimal pnlPct;
        if (pos.getIsLong()) {
            pnlPct = exitPrice.subtract(pos.getEntryPrice())
                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            pnlPct = pos.getEntryPrice().subtract(exitPrice)
                    .divide(pos.getEntryPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        double holdingHours = Duration.between(pos.getEntryTime(), exitTime).toMinutes() / 60.0;

        pos.setExitPrice(exitPrice);
        pos.setExitTime(exitTime);
        pos.setTpHit(tpHit);
        pos.setSlHit(slHit);
        pos.setPnlPct(pnlPct);
        pos.setHoldingHours(Math.round(holdingHours * 10.0) / 10.0);
        positionOutcomeRepository.save(pos);

        String reason = tpHit ? "TP HIT" : slHit ? "SL HIT" : "24h AUTO-CLOSE";
        log.info("Position closed: {} {} — {} at {} — P&L {}% ({}h)",
                pos.getSymbol(), pos.getSignalType(), reason, exitPrice,
                pnlPct.toPlainString(), String.format("%.1f", holdingHours));
    }

    /**
     * P&L summary: win rate, avg P&L, expectancy by signal type.
     */
    public Map<String, Object> getPnlSummary() {
        List<PositionOutcome> closed = positionOutcomeRepository.findByExitTimeIsNotNull();
        List<PositionOutcome> open = positionOutcomeRepository.findByExitTimeIsNull();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openPositions", open.size());
        result.put("closedPositions", closed.size());

        if (closed.isEmpty()) {
            result.put("message", "No closed positions yet — check back after signals fire and 24h elapses");
            return result;
        }

        // Overall stats
        result.put("overall", computeStats(closed));

        // By signal type
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        closed.stream()
                .collect(Collectors.groupingBy(p -> p.getSignalType().name()))
                .forEach((type, positions) -> byType.put(type, computeStats(positions)));
        result.put("bySignalType", byType);

        return result;
    }

    private Map<String, Object> computeStats(List<PositionOutcome> positions) {
        long wins = positions.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = positions.stream().filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) <= 0).count();
        double winRate = positions.isEmpty() ? 0 : (double) wins / positions.size() * 100;

        double avgPnl = positions.stream()
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double avgWin = positions.stream()
                .filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) > 0)
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double avgLoss = positions.stream()
                .filter(p -> p.getPnlPct().compareTo(BigDecimal.ZERO) <= 0)
                .mapToDouble(p -> p.getPnlPct().doubleValue())
                .average().orElse(0);

        double expectancy = (winRate / 100.0) * avgWin + (1 - winRate / 100.0) * avgLoss;

        long tpHits = positions.stream().filter(p -> Boolean.TRUE.equals(p.getTpHit())).count();
        long slHits = positions.stream().filter(p -> Boolean.TRUE.equals(p.getSlHit())).count();
        long autoCloses = positions.size() - tpHits - slHits;

        double avgHolding = positions.stream()
                .filter(p -> p.getHoldingHours() != null)
                .mapToDouble(PositionOutcome::getHoldingHours)
                .average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", positions.size());
        stats.put("wins", wins);
        stats.put("losses", losses);
        stats.put("winRatePct", Math.round(winRate * 10.0) / 10.0);
        stats.put("avgPnlPct", Math.round(avgPnl * 100.0) / 100.0);
        stats.put("avgWinPct", Math.round(avgWin * 100.0) / 100.0);
        stats.put("avgLossPct", Math.round(avgLoss * 100.0) / 100.0);
        stats.put("expectancyPct", Math.round(expectancy * 100.0) / 100.0);
        stats.put("tpHits", tpHits);
        stats.put("slHits", slHits);
        stats.put("autoCloses", autoCloses);
        stats.put("avgHoldingHours", Math.round(avgHolding * 10.0) / 10.0);
        return stats;
    }

    private double inferStopPercent(String symbol) {
        if (symbol.startsWith("IX.")) return stopPercentIndex;
        if (symbol.startsWith("CS.") || symbol.startsWith("CC.")) return stopPercentCommodity;
        return stopPercentCrypto;
    }
}
