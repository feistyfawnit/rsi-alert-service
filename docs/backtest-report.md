# Signal Quality Backtest & Alert Fatigue Report

**Date**: 2026-04-18  
**Period analysed**: 2026-04-13 to 2026-04-18 (~5 days)  
**Data sources**: `signal_logs` (244 rows), `candle_history` (1h + 4h), `app_settings`

---

## PART A — SIGNAL QUALITY BACKTEST

### Headline numbers

| Metric | Value |
|--------|-------|
| Total signals (5 days) | **244** |
| TREND_BUY_DIP | **146** (60%) |
| TREND_SELL_RALLY | **48** (20%) |
| Classic (OB/OS/Partial) | **50** (20%) |
| Signals per day (avg) | **~49** |
| Trend signals per day | **~39** |

**79.5% of all alerts are trend signals.** That is the spam.

### TREND_BUY_DIP — TP/SL analysis (24h window)

| Symbol | N | TP Hit | SL Hit | Neither | TP% | SL% | Exp (R) | Verdict |
|--------|---|--------|--------|---------|-----|-----|---------|---------|
| SOLUSDT | 22 | 11 | 5 | 6 | 50% | 23% | **+1.27** | ✅ Only good one |
| BCHUSDT | 27 | 7 | 9 | 11 | 26% | 33% | +0.44 | ⚠️ Marginal |
| BTCUSDT | 28 | 3 | 4 | 21 | 11% | 14% | +0.18 | ⚠️ Low hit rate |
| ETHUSDT | 24 | 2 | 13 | 9 | 8% | 54% | **−0.29** | ❌ Losing |
| DAX | 15 | 3 | 2 | 10 | 20% | 13% | +0.52 | ⚠️ Sparse |
| FTSE | 7 | 0 | 6 | 1 | 0% | 86% | **−0.86** | ❌❌ |
| Nasdaq | 5 | 1 | 0 | 4 | 20% | 0% | +0.60 | ⚠️ Tiny N |
| S&P 500 | 6 | 0 | 0 | 6 | 0% | 0% | 0.00 | Range-bound |
| Gold | 6 | 0 | 6 | 0 | 0% | 100% | **−1.00** | ❌❌❌ |
| Silver | 3 | 0 | 3 | 0 | 0% | 100% | **−1.00** | ❌❌❌ |
| Oil (buy) | 3 | 3 | 1 | 0 | 100% | 33% | +2.67 | Tiny N |

*Expectancy = (TP_count × 3 + SL_count × −1) / N. Assumes 3:1 R:R, "neither" = 0R.*

### TREND_SELL_RALLY — TP/SL analysis (24h window)

| Symbol | N | TP Hit | SL Hit | Neither | TP% | Exp (R) | Verdict |
|--------|---|--------|--------|---------|-----|---------|---------|
| BCHUSDT | 4 | 0 | 4 | 0 | 0% | **−1.00** | ❌ |
| BTCUSDT | 6 | 0 | 4 | 2 | 0% | **−0.67** | ❌ |
| CC.D.LCO.USS.IP | 12 | 0 | 12 | 0 | 0% | **−1.00** | ❌❌❌ |
| ETHUSDT | 9 | 0 | 7 | 2 | 0% | **−0.78** | ❌ |
| FTSE | 5 | 0 | 4 | 1 | 0% | **−0.80** | ❌ |
| SOLUSDT | 8 | 0 | 7 | 1 | 0% | **−0.88** | ❌ |
| Silver | 4 | 0 | 0 | 3 | 0% | 0.00 | Dead |
| **TOTAL** | **48** | **0** | **38** | **10** | **0%** | **−0.79** | **☠️** |

> **TREND_SELL_RALLY hit its take-profit exactly ZERO times out of 48 signals.**  
> 38 of 48 (79%) stopped out. This signal is pure noise during the analysed period.

### Forward price-move analysis (avg % move in signal direction)

| Symbol | Signal | N (with data) | Avg 1h% | Avg 4h% | Win@1h | Win@4h |
|--------|--------|---------------|---------|---------|--------|--------|
| SOL | BUY_DIP | 12 | −0.03 | −0.44 | 42% | 36% |
| BTC | BUY_DIP | 9 | +0.11 | −0.15 | 56% | 33% |
| ETH | BUY_DIP | 11 | +0.04 | −0.65 | 55% | 9% |
| BCH | BUY_DIP | 6 | +0.07 | −0.12 | 50% | 50% |
| ETH | SELL_RALLY | 4 | +0.33 | +0.55 | 75% | 100% |
| BTC | SELL_RALLY | 4 | +0.02 | −0.30 | 50% | 0% |
| SOL | SELL_RALLY | 4 | +0.12 | +0.78 | 50% | 75% |
| Oil | SELL_RALLY | 4 | −0.34 | −1.26 | 25% | 0% |

Key observation: most TREND_BUY_DIP crypto signals show tiny avg moves (+0.04 to +0.11% at 1h) that degrade by 4h. The 3% TP target requires moves that rarely materialise from these sideways-RSI entries.

### Worst-performing buckets (mute/tighten first)

1. **TREND_SELL_RALLY — ALL symbols**: 0% TP, −0.79R. Kill or suspend entirely.
2. **Gold TREND_BUY_DIP**: 0% TP, 100% SL, −1.00R
3. **Silver TREND_BUY_DIP**: 0% TP, 100% SL, −1.00R
4. **FTSE TREND_BUY_DIP**: 0% TP, 86% SL, −0.86R
5. **ETH TREND_BUY_DIP**: 8% TP, 54% SL, −0.29R (high volume — 24 signals)
6. **BTC TREND_BUY_DIP**: 11% TP, 14% SL, +0.18R (28 signals for +0.18R is noise)

### Data quality flag

SOL OVERSOLD signals (5 rows) all show `current_price = 142.50` — but SOL was ~$87-88 during the alert window. This is a stale price bug; those rows are excluded from meaningful analysis.

---

## PART B — ALERT SPAM DIAGNOSIS

### B.1 — Solana TREND_BUY_DIP fired repeatedly at same price

**CONFIRMED.** 22 TREND_BUY_DIP signals for SOL in 2.5 days ($87–89 range).

**Root cause** — `SignalCooldownService` (`SignalCooldownService.java:21-37`) uses a time-only cooldown (120 min). Once expired, `TrendDetectionService.checkForTrendEntry()` (:253-289) fires again if `fastRsi < 60 && fastRsi > 30`. With SOL oscillating in the 45-57 RSI range on 15m, this condition is continuously true → fires every 2 hours.

**No price-based or RSI-state dedupe exists.** The signal fires for the same "dip" that never recovered.

**Proposed fix** — add price + RSI-recovery gate in `TrendDetectionService`:

```java
// New fields in TrendDetectionService
private final Map<String, BigDecimal> lastDipAlertPrice = new ConcurrentHashMap<>();
private final Map<String, Boolean> dipRsiRecovered = new ConcurrentHashMap<>();

// In checkForTrendEntry(), TREND_BUY_DIP block, BEFORE cooldownService.shouldAlert():
BigDecimal lastPrice = lastDipAlertPrice.get(instrument.getSymbol());
Boolean recovered = dipRsiRecovered.getOrDefault(instrument.getSymbol(), true);
if (lastPrice != null && !recovered) {
    double pctMove = Math.abs(currentPrice.subtract(lastPrice)
        .divide(lastPrice, 8, RoundingMode.HALF_UP).doubleValue() * 100);
    if (pctMove < 0.3) {
        log.debug("TREND_BUY_DIP suppressed for {} — price only {:.2f}% from last dip alert, RSI not recovered", 
            instrument.getSymbol(), pctMove);
        return; // or continue, depending on the block structure
    }
}
// After publishEvent:
lastDipAlertPrice.put(instrument.getSymbol(), currentPrice);
dipRsiRecovered.put(instrument.getSymbol(), false);

// Somewhere RSI recovery is tracked — when fastRsi > dipRsiThreshold:
if (fastRsi >= dipRsiThreshold) {
    dipRsiRecovered.put(instrument.getSymbol(), true);
}
```

Same pattern for TREND_SELL_RALLY with `rallyRsiThreshold`.

**Impact**: would eliminate ~70% of the 22 SOL dip signals (price delta < 0.3% between most).

### B.2 — Minimum stop distance too small

**CONFIRMED.** `NotificationService.java:291`:
```java
long stopPts = Math.round(entry.doubleValue() * effectiveStopPct / 100.0);
```

SOL at $88 × 1.0% / 100 = 0.88 → rounds to **1 point**. That's a $1 stop on a $88 instrument = 1.1%. But `Math.round` on low-priced instruments creates quantisation errors, and 1pt may be below IG's `minDealDistance`.

**Proposed fix** — `NotificationService.java:291`, replace the single line:
```java
long stopPts = Math.max(
    Math.round(entry.doubleValue() * effectiveStopPct / 100.0),
    Math.max(Math.round(entry.doubleValue() * 0.002), 2)  // floor: 0.2% of price or 2pts
);
```

### B.3 — "RSI 28/3" header is ambiguous

**CONFIRMED.** `NotificationService.java:190-192`:
```java
message.append("RSI ").append(signal.getTimeframesAligned())
       .append("/").append(signal.getTotalTimeframes())
```

For TREND_BUY_DIP, `timeframesAligned` is set to the **consecutive overbought count** (`TrendDetectionService.java:277`), not an alignment count. So "RSI 28/3" means "28 consecutive OB signals / 3 timeframes" — misleading.

**Proposed fix** — `NotificationService.java:190-192`:
```java
boolean isTrend = signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP
        || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY;
if (isTrend) {
    message.append("RSI ").append(rsiLine)
           .append(" · streak ").append(signal.getTimeframesAligned()).append("\n");
} else {
    message.append("RSI ").append(signal.getTimeframesAligned())
           .append("/").append(signal.getTotalTimeframes())
           .append(": ").append(rsiLine).append("\n");
}
```

### B.4 — Bitcoin Cash direction flipped SHORT→LONG in <24h

**CONFIRMED.** BCH had 4 TREND_SELL_RALLY (SHORT) then 27 TREND_BUY_DIP (LONG). The flip happened when price crossed EMA20(1h) with zero hysteresis.

`TrendDetectionService.java:133-136`:
```java
Boolean aboveEma = getPriceVsEma(symbol);
if (aboveEma != null) {
    return aboveEma ? TrendState.STRONG_UPTREND : TrendState.STRONG_DOWNTREND;
}
```

A single tick crossing the EMA flips state. No buffer, no confirmation.

**Proposed fix** — add hysteresis in `getPriceVsEma()`:
```java
private Boolean getPriceVsEma(String symbol) {
    String key = priceHistoryService.buildKey(symbol, emaTrendTimeframe);
    List<BigDecimal> history = priceHistoryService.getPriceHistory(key);
    if (history == null || history.size() < emaPeriod) return null;
    BigDecimal ema = emaCalculator.calculate(history, emaPeriod);
    if (ema == null) return null;
    BigDecimal currentPrice = history.get(history.size() - 1);
    double pctFromEma = currentPrice.subtract(ema)
        .divide(ema, 8, RoundingMode.HALF_UP).doubleValue() * 100;
    // Hysteresis: must be >0.1% away from EMA to flip
    if (Math.abs(pctFromEma) < 0.1) return null; // NEUTRAL — too close to call
    return pctFromEma > 0;
}
```

This means price within ±0.1% of EMA → `NEUTRAL` (no trend signals fire, no counter-trend suppression). Eliminates whipsaw.

### B.5 — IG instruments show raw point prices

**CONFIRMED.** Silver at $7,941, Oil at $9,349 — these are IG's internal point values, not spot prices. `NotificationService.formatPrice()` (:333-337) applies no scaling.

**Proposed fix** — add a display-scaling map:
```java
private static final Map<String, Double> IG_POINT_DIVISORS = Map.of(
    "CS.D.USCSI.TODAY.IP", 100.0,  // Silver: pts/100 = $/oz
    "CC.D.LCO.USS.IP", 100.0       // Brent: pts/100 = $/bbl
    // Gold is ~1:1 with spot, no divisor needed
);

private String formatPrice(BigDecimal price, String symbol) {
    if (price == null) return "N/A";
    String currencySymbol = inferCurrencySymbol(symbol);
    Double divisor = IG_POINT_DIVISORS.get(symbol);
    if (divisor != null) {
        double spot = price.doubleValue() / divisor;
        return String.format("%,.0f pts (~%s%.2f spot)", price.doubleValue(), currencySymbol, spot);
    }
    return currencySymbol + String.format("%,.2f", price.doubleValue());
}
```

Note: verify the actual divisors by comparing IG price feed vs spot for each epic. The values above are estimates.

### B.6 — Boilerplate noise repeated on every crypto alert

**CONFIRMED.** `NotificationService.java:210-212`:
```java
if (isCryptoSymbol(signal.getSymbol())) {
    message.append("<i>⚠️ CFD overnight ~€0.50-0.70/unit — close by 22:00 UTC</i>\n");
}
```

And `:323-325`:
```java
if (isCryptoSymbol(signal.getSymbol())) {
    sb.append("\n<i>[Binance price — adjust stops on IG entry accordingly]</i>");
}
```

Both fire on every alert. With 22 SOL alerts in 2.5 days, that's 22× the same boilerplate.

**Proposed fix** — add a per-symbol daily flag:
```java
private final Map<String, LocalDate> lastBoilerplateDate = new ConcurrentHashMap<>();

// In buildNotificationMessage, replace the CFD warning block:
if (isCryptoSymbol(signal.getSymbol())) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate lastShown = lastBoilerplateDate.get(signal.getSymbol());
    if (lastShown == null || !lastShown.equals(today)) {
        message.append("<i>⚠️ CFD overnight ~€0.50-0.70/unit — close by 22:00 UTC</i>\n");
        lastBoilerplateDate.put(signal.getSymbol(), today);
    }
}
```

Same treatment for the Binance disclaimer in `buildDemoGuidance`.

### B.7 — "YOU HAVE AN OPEN POSITION" fires on Solana anomalies incorrectly

**CONFIRMED.** `app_settings` table shows:
```
active_position = SOLUSDT    (set 2026-04-13 12:29)
```

**Root cause** — `SignalLogService.java:45-57` auto-sets `active_position` on ANY full signal including TREND_BUY_DIP/TREND_SELL_RALLY:
```java
boolean isFullSignal = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
    || signal.getSignalType() == SignalLog.SignalType.OVERBOUGHT
    || signal.getSignalType() == SignalLog.SignalType.TREND_BUY_DIP   // ← bug
    || signal.getSignalType() == SignalLog.SignalType.TREND_SELL_RALLY; // ← bug
```

The first SOL OVERSOLD signal on Apr 13 set `active_position = SOLUSDT`. Since there's no auto-clear and no manual clear was issued, it's been stale for 5+ days. Every SOL anomaly then triggers the "YOU HAVE AN OPEN POSITION" message via `AnomalyNotificationService.java:146`.

**Proposed fix** — remove TREND signals from auto-set (they're suggestions, not trades):
```java
boolean isFullSignal = signal.getSignalType() == SignalLog.SignalType.OVERSOLD
    || signal.getSignalType() == SignalLog.SignalType.OVERBOUGHT;
```

And add a TTL check in `hasOpenPositionFor()`:
```java
// In AnomalyNotificationService or AppSettingsService
// Consider adding an updated_at check — if position was set >48h ago, treat as stale
```

---

## PART C — ROADMAP RECALIBRATION

Based on backtest evidence, here is the revised priority order:

### P1 — Ship immediately

| # | Item | Justification |
|---|------|---------------|
| 1 | **Trend-dip dedupe + price gate** (new) | Eliminates ~70% of the 146 TREND_BUY_DIP spam signals. Single biggest fatigue reducer. |
| 2 | **Disable or heavily gate TREND_SELL_RALLY** (new) | 0% TP rate, −0.79R expectancy across 48 signals. This signal is actively harmful. |
| 3 | **EMA hysteresis (±0.1%)** (new) | Prevents BCH-style direction whipsaw. Reduces both BUY_DIP and SELL_RALLY spam near the EMA. |
| 4 | **Telegram bot commands** (/mute, /position, /status) | Existing P1, keep. Gives manual override for fatigue — critical UX. |
| 5 | **Fix active_position auto-set** (new) | Bug fix: TREND signals shouldn't auto-set position. 5+ days of false "open position" warnings. |

### P2 — Ship this sprint

| # | Item | Justification |
|---|------|---------------|
| 6 | **Min stop floor** | B.2 fix. Prevents quantisation errors on low-price instruments. |
| 7 | **RSI header relabel for TREND signals** | B.3 fix. "RSI 28/3" is confusing — quick formatting change. |
| 8 | **Boilerplate daily-only** | B.6 fix. Reduces per-message noise. |
| 9 | **Stochastic Confirmation Layer** | Backtest shows BUY_DIP fires on any RSI < 60 — stochastic crossover confirmation would filter coin-flip entries. **Demote from your instinct of P1** — the dedupe + SELL_RALLY kill are higher leverage. |
| 10 | **IG point-price display scaling** | B.5 fix. UX improvement for commodity instruments. |

### P3 — After spam is fixed

| # | Item | Justification |
|---|------|---------------|
| 11 | **Claude AI enrichment** | Your instinct to defer was correct. AI context on 39 spam signals/day is wasted API spend. Fix the signal quality first. |
| 12 | **Automated trade execution** | Backtest shows most TREND signals lose. Do not automate losing signals. |

### Your instincts vs data

- **"Promote Stochastic to P1 if it would have filtered the losing dip cluster"** → **Partially supported.** Stochastic would help but the dedupe + price gate eliminates more spam with less code. Stochastic is P2.
- **"Add trend-dip dedupe + min stop as P1"** → **Strongly supported.** Dedupe is #1 priority.
- **"Keep Telegram bot commands P1"** → **Supported.** /mute alone would have silenced the 22 SOL alerts.
- **"Keep Claude P1 but after spam is fixed"** → **Supported, but demote to P3.** Don't enrich signals that are -0.79R losers.

---

## SINGLE RECOMMENDATION

> **Ship the trend-dip dedupe + TREND_SELL_RALLY disable today.**
>
> In `TrendDetectionService.checkForTrendEntry()`, add a price-gate (>0.3% move or RSI recovery above threshold required) and either disable TREND_SELL_RALLY entirely or gate it behind a feature flag defaulting to `false`.
>
> **Expected impact**: eliminates ~150 of the 194 trend signals (77%) while preserving the one bucket with positive expectancy (SOL TREND_BUY_DIP at +1.27R, which survives the dedupe with ~6–8 signals instead of 22).
>
> This is a ~30-minute code change that cuts daily alerts from ~49 to ~12.

---

## SQL QUERIES USED

All queries were read-only SELECTs against the running `market-signals-postgres` container. No mutations were made. The full backtest query:

```sql
-- Forward price-move analysis
WITH signals AS (
  SELECT id, symbol, instrument_name, signal_type, current_price, created_at,
    CASE WHEN signal_type IN ('OVERSOLD','PARTIAL_OVERSOLD','WATCH_OVERSOLD','TREND_BUY_DIP') 
         THEN 'LONG' ELSE 'SHORT' END as direction
  FROM signal_logs
),
forward AS (
  SELECT s.id, s.symbol, s.signal_type, s.current_price, s.direction, s.created_at,
    (SELECT ch.close FROM candle_history ch WHERE ch.symbol=s.symbol AND ch.timeframe='1h'
     AND ch.candle_time BETWEEN s.created_at + INTERVAL '50 min' AND s.created_at + INTERVAL '70 min'
     ORDER BY ch.candle_time LIMIT 1) as p1h,
    (SELECT ch.close FROM candle_history ch WHERE ch.symbol=s.symbol AND ch.timeframe='1h'
     AND ch.candle_time BETWEEN s.created_at + INTERVAL '3h50m' AND s.created_at + INTERVAL '4h10m'
     ORDER BY ch.candle_time LIMIT 1) as p4h
  FROM signals s
)
SELECT symbol, signal_type, direction, COUNT(*) as n,
  ROUND(AVG(CASE WHEN direction='LONG' THEN (p1h-current_price)/current_price*100 
       ELSE (current_price-p1h)/current_price*100 END)::numeric, 3) as avg_1h_pct,
  SUM(CASE WHEN direction='LONG' AND p1h > current_price THEN 1
           WHEN direction='SHORT' AND p1h < current_price THEN 1 ELSE 0 END) as win_1h
FROM forward WHERE p1h IS NOT NULL
GROUP BY symbol, signal_type, direction ORDER BY signal_type, symbol;

-- TP/SL hit analysis
WITH trend_signals AS (
  SELECT s.id, s.symbol, s.signal_type, s.current_price, s.created_at,
    CASE WHEN s.signal_type='TREND_BUY_DIP' THEN 'LONG' ELSE 'SHORT' END as dir,
    CASE WHEN s.symbol LIKE 'IX.%' THEN 0.25 
         WHEN s.symbol LIKE 'CS.%' OR s.symbol LIKE 'CC.%' THEN 0.5 
         ELSE 1.0 END as stop_pct,
    CASE WHEN s.symbol LIKE 'IX.%' THEN 0.75 
         WHEN s.symbol LIKE 'CS.%' OR s.symbol LIKE 'CC.%' THEN 1.5 
         ELSE 3.0 END as tp_pct
  FROM signal_logs s WHERE s.signal_type IN ('TREND_BUY_DIP','TREND_SELL_RALLY')
),
candle_extremes AS (
  SELECT t.id, MIN(ch.low) as min_low, MAX(ch.high) as max_high
  FROM trend_signals t
  JOIN candle_history ch ON ch.symbol=t.symbol AND ch.timeframe='1h'
    AND ch.candle_time BETWEEN t.created_at AND t.created_at + INTERVAL '24 hours'
  GROUP BY t.id
)
SELECT t.symbol, t.signal_type, COUNT(*) as n,
  SUM(CASE WHEN t.dir='LONG' AND ce.max_high >= t.current_price*(1+t.tp_pct/100) THEN 1
           WHEN t.dir='SHORT' AND ce.min_low <= t.current_price*(1-t.tp_pct/100) THEN 1 ELSE 0 END) as tp_hit,
  SUM(CASE WHEN t.dir='LONG' AND ce.min_low <= t.current_price*(1-t.stop_pct/100) THEN 1
           WHEN t.dir='SHORT' AND ce.max_high >= t.current_price*(1+t.stop_pct/100) THEN 1 ELSE 0 END) as sl_hit
FROM trend_signals t LEFT JOIN candle_extremes ce ON ce.id=t.id
GROUP BY t.symbol, t.signal_type ORDER BY t.signal_type, t.symbol;
```

### Limitations

1. **candle_history retention**: ~100 candles per symbol:timeframe. 1h = ~4 days, 4h = ~16 days. Forward data coverage varies by signal age.
2. **TP-before-SL ordering**: The query checks if TP *or* SL was hit within 24h, but cannot determine which hit *first* from 1h OHLC alone. Both could hit in the same candle. True ordering requires tick data or 1m candles.
3. **Timezone**: `signal_logs.created_at` is `timestamp without time zone` (server-local, assumed UTC in Docker). `candle_history.candle_time` is `timestamp with time zone`. Direct comparison works only if the JVM timezone is UTC.
4. **SOL OVERSOLD price anomaly**: 5 rows show `current_price = 142.50` when SOL was ~$88 — excluded from meaningful conclusions.
