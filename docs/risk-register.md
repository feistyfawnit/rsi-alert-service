# LucidLynx Market Signals — Risk Register & Open Items

*Last updated: April 2026*
*Renamed from Windsurf.md — April 2026*

---

> ⚠️ **IG API DATA ALLOWANCE — CRITICAL**
> IG permits **10,000 data points/week**. Exceeding this returns HTTP 403 (`exceeded-account-historical-data-allowance`). **This occurred April 8, 2026.**
> - Current safe budget: ~5,300 pts/week (IG polling every 15 min, 7 IG instruments with candle-period skip)
> - Each candle fetch = 1 data point; warmup fetches 28 at once
> - **Do NOT**: add IG instruments, shorten IG polling interval, or trigger bulk warmup without recalculating budget
> - **Do NOT**: make ad-hoc IG price/candle API calls without counting the cost

---

## Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| IG API rate limit ban | Medium | High | Exponential backoff; respect limits; demo-first |
| False signals in choppy markets | High | Medium | ✅ ADX filter deployed Apr 24 — ADX(14)>20 required for trend entries |
| RSI calculation bugs | Low | High | Unit test against TA-Lib; backtest on historical data |
| Railway downtime | Low | Medium | Health check monitoring; email alert on crash |
| Market closure edge case | Medium | Low | Skip polling outside market hours per instrument |
| IG 10k/week data allowance exceeded | **Occurred** | **Critical** | Split polling (IG 15 min), candle-period skip, stale DB cleanup; see April 7 incident |

## Business / Trading Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Strategy stops working | Medium | High | Monthly performance review; kill switch if drawdown >5% |
| Trend detection false positive | Low | Medium | Tighter stops (half normal) on TREND_BUY_DIP/TREND_SELL_RALLY; trend expires after 12h if no confirming signal |
| Overfitting to recent conditions | Medium | Medium | Backtest across multi-year bull/bear data |
| Auto-execution bug (Phase 4) | Low | **Critical** | Extensive demo testing; manual approval; position limits |
| MiFID II breach if shared | Low | **Critical** | Never distribute; keep private; personal use documented |

---

## Open Items

- **Backtesting** — fetch 3–6 months historical data, replay signals before live trading
- **Market hours** — skip polling closed markets (DAX 08:00–22:00 CET, FTSE 08:00–16:30 GMT); crypto 24/7
- **Staggered polling** — 1m TF: 15s, 5m TF: 30s, 1h/4h TF: 2–5 min — reduces IG API calls ~60%
- **DB/YAML instrument sync** — when epic codes change in `application.yml`, stale DB rows must be disabled/deleted manually. JPA `ddl-auto: update` does not remove old rows. Consider a startup reconciliation job that disables DB instruments not present in YAML.

---

## Configuration Decisions Log

| Date | Decision | Reason |
|------|----------|--------|
| Mar 2026 | Polling interval: 60s (was 30s) | IG rate limit is 30 req/min; 4 IG instruments × 3 timeframes = 12 calls/cycle. 60s keeps us at ~12/min with headroom |
| Mar 2026 | Timeframes: `15m,1h,4h` per instrument | Matches Brian's strategy; 4h moves slowly enough that 60s polling adds zero lag |
| Mar 2026 | Gold epic: `CS.D.USCGC.TODAY.IP` (Spot Gold, DFB) | `CC.D.XAUUSD.CFD.IP` failed — verified correct epic via IG search API |
| Mar 2026 | FTSE epic: `IX.D.FTSE.DAILY.IP` | Confirmed via IG search API |
| Mar 2026 | S&P 500 epic: `IX.D.SPTRD.DAILY.IP` | Confirmed via IG search API |
| Mar 2026 | IG Base URL: `https://api.ig.com` (live) | Demo API doesn't serve market data; live API works fine with demo account for trading |
| Mar 2026 | Warmup backoff: 2m → 5m → 15m → 60m | Prevents hammering IG API every 30s when an epic is unavailable or market is closed |
| Mar 2026 | Active instruments: SOL, BTC, ETH, BCH (Binance) + DAX, FTSE, S&P 500, Gold (IG) | 4 IG instruments is the safe ceiling at current polling rate without increasing interval |
| Mar 2026 | Java 21 → Java 25 (LTS) | Upgrade required Spring Boot 3.2.3 → 3.5.3 (ASM 9.8+ for class version 69), Lombok 1.18.36 → 1.18.38, and explicit annotationProcessorPaths in maven-compiler-plugin |
| Mar 2026 | Volume spike threshold: 4.0σ → 5.5σ, lookback 20→30, min-periods 10→20 | Too many false positives at market open (8am DAX/FTSE) — baseline built from overnight low-volume data makes open look like 22σ spike. Also cold-start sensitivity after container restart. |
| 8 Apr 2026 | IG 15 min polling + candle-period skip + 403 handling | IG 10k/week data point allowance exceeded: 8 enabled instruments at 5 min interval = ~37k/week (3.7× over). Split polling: Binance 5 min, IG 15 min. Candle-period skip avoids redundant fetches. 403 handler detects `exceeded-account-historical-data-allowance` (no session invalidation). Per-epic tracking prevents cascade. Disabled stale DB records (`CS.D.USCGC.TODAY.IP`, `CC.D.LCO.FSD.IP`). New budget: ~4,700/week. |
| 14 Apr 2026 | Trend Detection + TREND_BUY_DIP / TREND_SELL_RALLY | RSI overbought sell signals were losing money in strong uptrends. Added `TrendDetectionService` to track consecutive signals (3+ → strong trend). Suppresses counter-trend signals. Generates trend-following signals (buy the dip in uptrend, sell the rally in downtrend) with tighter stops (half normal) and 3:1 R:R. Config: `rsi.trend.consecutive-signals-for-trend=3`, `rsi.trend.suppress-counter-trend=true`. |

---

*Ivan T & Brian H — Private Use*
