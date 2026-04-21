# LucidLynx Market Signals — Phase Completion Status & Next Steps

*All phases 1–5 scaffolded March–April 2026*

---

## ✅ Phase 1 — Core Multi-Indicator Alert Engine — COMPLETE

- Spring Boot app running in Docker
- Binance API for crypto: SOL (live), BTC (re-enabled Apr 21 — history collection, TREND_BUY_DIP suppressed), ETH/BCH (disabled)
- RSI calculation across 15m / 1h / 4h timeframes
- Push notifications on signal — live (originally ntfy.sh, migrated to Telegram April 2026)
- PostgreSQL watchlist with CRUD REST API
- Configurable thresholds, cooldown, quiet hours
- **Trend Detection** (April 2026) — tracks consecutive overbought/oversold signals, suppresses counter-trend signals, generates TREND_BUY_DIP / TREND_SELL_RALLY alerts

---

## ✅ Phase 2 — IG API Integration — COMPLETE

- IG live API authenticated (spread bet account)
- DAX 40, FTSE, S&P 500, Gold, Oil seeded — enable via YAML
- Session auto-refresh every 6 hours
- DataInitializer upserts instruments from YAML on every restart
- Test endpoints: `/api/test/notify`, `/api/test/lower-thresholds`, `/api/test/ig/search`

---

## ✅ Phase 3 — AI Layer — INFRASTRUCTURE BUILT, API KEY NEEDED

- `ClaudeEnrichmentService` built and wired into `NotificationService`
- Disabled by default (`CLAUDE_ENABLED=false`)
- Enable with: `CLAUDE_ENABLED=true` + `CLAUDE_API_KEY` from console.anthropic.com
- ~$5 of Haiku credits = weeks/months of enriched alerts at current signal frequency

---

## ✅ Phase 4 — Semi-Automated Trading — SCAFFOLDED, HARD-DISABLED

⚠️ **HIGH RISK — DO NOT ENABLE WITHOUT DEMO VALIDATION** ⚠️

- `IGTradingService` built with kill switch, daily loss limit, max concurrent positions
- Requires manual approval flag by default (`TRADING_REQUIRE_MANUAL_APPROVAL=true`)
- Hard-disabled: `TRADING_AUTO_EXECUTION_ENABLED=false`

### Prerequisites before enabling (non-negotiable):
- [ ] 3+ months of signal validation (paper trading)
- [ ] Proven positive P&L in paper trading
- [ ] IG demo account testing completed (minimum 1 month)
- [ ] Risk management parameters reviewed and set conservatively
- [ ] Kill switch tested and confirmed working

### Risk Management (mandatory config):
- **Max position size:** 2% of capital per trade (configurable)
- **Max concurrent positions:** 2 (configurable)
- **Daily loss limit:** 2% of account balance
- **Manual approval:** required for every auto-trade initially

Kill switch: `POST /api/trading/kill-switch/activate`

---

## ⏳ Phase 5 — Anomaly / Geopolitical Detection — PARTIALLY BUILT

### ✅ Done:
- `VolumeAnomalyDetector` — live, fires on 4σ volume spikes per instrument
- `AnomalyNotificationService` — urgent ntfy.sh alerts bypassing quiet hours
- `AnomalyProperties` / `AnomalyEvent` / `AnomalyAlert` model — complete
- `application.yml` anomaly config including Polymarket market slugs

### ✅ Also built:
- **`PolymarketMonitorService`** — polls configured Polymarket slugs every 5 min, fires anomaly alerts on odds shifts ≥8pp

### ⏳ Not yet built:
- **Cross-instrument correlation detector** — planned, not started
- **High Uncertainty Mode toggle** — planned, not started

See Section 11 of `rsi-alert-tool-requirements.md` for full specification. See `RISK_REGISTER.md` for risks and config decisions.

---

## Data Source Strategy

| Source | Cost | Coverage | Status |
|--------|------|----------|--------|
| Binance | FREE | Crypto (SOL, BTC, ETH, BCH) | ✅ Live |
| IG API | FREE (with account) | Indices, FX, commodities, crypto | ✅ Live |
| Finnhub | Paid for indices | Stocks, some indices | ❌ Free tier insufficient |
| Twelve Data | Paid for indices | Stocks, crypto | ❌ Rate limits too low (8/min) |

---

## Cost Summary

| Phase | Monthly Cost | Status |
|-------|-------------|--------|
| Phase 1 (crypto RSI + trend detection) | $0 local / $0 AWS Free Tier / ~€15 post-free | ✅ Ready to deploy |
| Phase 2 (IG indices) | $0-15 AWS | ✅ Ready — enable IG credentials |
| Phase 3 (Claude AI) | $7-10 | ✅ Ready — add `CLAUDE_API_KEY` |
| Phase 4 (auto-trading) | $5-10 | ⛔ Do not enable without 3+ months paper trading |
| Phase 5 (anomaly) | $0 | ⏳ Volume spike + Polymarket monitor live; cross-correlation not built yet |

### Hosting Comparison

| | **Render** | **Railway** | **AWS Free Tier** |
|--|------------|-------------|-------------------|
| **Cost** | $0 (sleeps) / $7+ always-on | $5/mo flat | $0 for 12 months, then ~$15-20/mo |
| **Sleeps?** | Yes — free tier spins down after 15min | No | No |
| **CV Value** | Low | Low | **High** — shows AWS/container skills |
| **PostgreSQL** | Paid add-on | Built-in | RDS free tier or EC2 self-hosted |
| **Complexity** | Low | Low | Medium — VPC, IAM, ECS/Fargate |
| **SSL/Domain** | Automatic | Automatic | Manual ACM setup |
| **CI/CD** | GitHub auto-deploy | GitHub auto-deploy | GitHub Actions → ECR → ECS |
| **Best for** | Quick demos | Production, always-on | Learning AWS, CV building |

---

## ✅ Recent Addition — Trend Detection v2 + v2.1 (April 14–16 2026)

**Problem:** You were shorting into strong uptrends — RSI overbought signals fire repeatedly in bull markets, but the trend keeps running up. The original fix (counting 3+ consecutive signals) still let you enter 1–2 bad trades before suppression kicked in. A second issue (cold-start): EMA50 required 50+ hourly candles, leaving a ~50h window where trend state was NEUTRAL and counter-trend signals were not suppressed.

**Solution:** `TrendDetectionService` uses **EMA 20 on the 1h timeframe** as the primary trend filter (available immediately after the 28-candle warmup), with two fallbacks:

| Condition | Action |
|-----------|--------|
| Price above EMA20(1h) | `STRONG_UPTREND` — suppress OVERBOUGHT sell signals immediately |
| Price below EMA20(1h) | `STRONG_DOWNTREND` — suppress OVERSOLD buy signals immediately |
| EMA history insufficient (< 20 candles) | Fallback 1: price momentum over last 5 candles |
| Price moved >1% over last 5 1h candles | `STRONG_UPTREND` or `STRONG_DOWNTREND` via momentum |
| Neither EMA nor momentum available | Fallback 2: 2+ consecutive same-direction signals |

**Dip entry (TREND_BUY_DIP):** Fires when fastest-TF RSI drops **below 60** (pulled back from overbought >70) while price remains **above EMA20(1h)**. This is the classic "buy the dip in an uptrend" setup — momentum cooled, structure intact.

**Rally entry (TREND_SELL_RALLY):** Mirror — RSI bounces **above 40** while price **below EMA20(1h)**.

**Risk settings for trend trades:**
- Stop: half normal width (e.g. 0.25% for indices vs 0.5% standard)
- Limit: 3× stop distance (3:1 R:R vs 2:1 standard)

**Config:** `rsi.trend.ema-period: 20`, `rsi.trend.ema-timeframe: 1h`, `rsi.trend.consecutive-signals-for-trend: 2` (fallback)

**Notification:** `🐂 STRONG UPTREND — price above EMA20(1h)` or `🐂 STRONG UPTREND — 1.2% move in 5 1h candles (momentum proxy)`

**Result:** From warmup completion (~28h), if price sits above its 20-hour EMA, the next sell signal is suppressed *immediately* — no more losing shorts into bull markets. You instead get **📈 TREND BUY (Dip)** when RSI cools toward 60.

---

## Prioritised Backlog

> Timelines assume part-time development (~2–4 hrs/week). P1 = do soon, P2 = next sprint, P3 = future.

### 🔴 P1 — High Value, Do Soon (this week / next 2 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **✅ Duplicate position guard** | **COMPLETE** | `PositionOutcomeService.handleSignalEvent()` skips creating a new `PositionOutcome` if an open position already exists for the same symbol. Prevents report overcounting. Mirrors real-world single-position-per-instrument constraint. |
| **✅ Candle History Persistence (DB)** | **COMPLETE** | `CandleHistory` entity persists to PostgreSQL, 2,885+ candles loaded on startup, RSI accuracy maintained across restarts. |
| **✅ P&L Report — EUR totals + CSV endpoint** | **COMPLETE** | `PositionReportService` now shows Risk/trade, Gross wins, Gross losses, Net P&L in EUR. By Instrument breakdown added. New endpoint: `GET /api/positions/pnl-report/csv`. Makefile: `make remote-report` / `make remote-csv`. |
| **RSI-bucket outcome analysis** (data task) | ~1h SQL | Run once ≥2 weeks of `position_outcomes` data exists (seeded Apr 18). Join `signal_logs` + `position_outcomes` to split TREND_BUY_DIP wins/losses by rsi15m bucket (<35, 35–45, 45–50, 50–55, 55–60). If <50 fires show materially higher win rate, lower `TREND_DIP_RSI_THRESHOLD` from 60 → 50 (config-only). BTC re-enabled Apr 21 to accelerate data collection. Query: `SELECT CASE WHEN s.rsi15m < 35 THEN '<35' WHEN s.rsi15m < 45 THEN '35-45' WHEN s.rsi15m < 50 THEN '45-50' WHEN s.rsi15m < 55 THEN '50-55' ELSE '55-60' END as bucket, COUNT(*) as n, SUM(CASE WHEN p.outcome = 'TP_HIT' THEN 1 ELSE 0 END) as tp, SUM(CASE WHEN p.outcome = 'SL_HIT' THEN 1 ELSE 0 END) as sl FROM signal_logs s JOIN position_outcomes p ON p.signal_log_id = s.id WHERE s.signal_type = 'TREND_BUY_DIP' GROUP BY 1 ORDER BY 1;` |
| **Telegram Bot Commands** | ~3h | Manage the service via Telegram messages instead of curl/API. Commands: `/position BTCUSDT` (set active position → enables anomaly monitoring), `/close` (clear position → disables anomaly monitoring), `/status` (show position, no-trade mode, muted symbols), `/mute BTCUSDT` / `/unmute BTCUSDT`, `/notrade on` / `/notrade off`. Requires a Telegram webhook or polling listener (`TelegramCommandService`) that parses incoming messages and calls `AppSettingsService`. Admin-only — restrict to known chat IDs. |
| **Momentum Fading Detector** | ~2h | Notify "FAST TF DIVERGENCE — consider taking profit" when 3/3 aligned but fast TFs (1m–15m) flip opposite. No new API calls. Uses existing RSI values. Exit timing signal — replaces manual chart check. |
| **✅ Deploy to AWS** | **COMPLETE** | Live on `108.128.230.238` (eu-west-1). 5 instruments active. See `docs/remote-deployment.md`. |
| **Enable Claude AI enrichment** | ~30min | Add `CLAUDE_API_KEY` env var — service already built. ~$5/month at current signal frequency. |

### 🟡 P2 — High Value, Medium Effort (2–4 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **Price Momentum Surge Detector** | ~4h | Detects rapid price moves (>0.5% in 15min or >1% in 1h) *before* RSI aligns. Require simultaneous surge across 2+ indices (e.g. S&P + DAX) to filter noise. Bidirectional. Catches institutional flow / news leaks like the April 7 pre-announcement buying. Uses existing candle data — no new API calls. |
| **Stochastic Confirmation Layer** | ~3h | Add %K (14,3,3) as optional confirmation on RSI signals. Computable from existing OHLC data. See `PROJECT_LOG.md` for proposed logic. |
| **Self-Service Telegram Onboarding** | ~3h | New users message bot `/start` → admin gets DM *"@username (987654321) requests alerts — /approve 987654321 /reject 987654321"*. Approved IDs hot-reloaded into `TELEGRAM_CHAT_IDS` without restart. Store `PendingSubscriber` in DB with approval audit trail. Eliminates manual `curl getUpdates` step. |

### 🟢 P3 — Lower Priority / Speculative (1–3 months)

| Item | Effort | Notes |
|------|--------|-------|
| **Cross-instrument Correlation Detector** | ~6h | Flag when 3+ indices align simultaneously (e.g. DAX + FTSE + S&P all oversold) — stronger signal. Part of Phase 5 spec. |
| **High Uncertainty Mode Toggle** | ~2h | Suppress all signals except urgent full-alignment when VIX-equivalent is elevated. Part of Phase 5 spec. |
| **Phase 4 Auto-Trading (enable)** | weeks | Hard-disabled. Requires 3+ months paper trading first. Do not rush. |

---

## Immediate Next Actions

1. ✅ App is running — `make logs` (local) · `make remote-logs` (AWS EC2) · `make ship` (deploy + watch)
2. **Paper trade** — log every signal manually for 4–8 weeks before trusting Phase 4
3. **Deploy to AWS** — for 24/7 uptime. See `docs/remote-deployment.md`.
4. **Add `CLAUDE_API_KEY`** — quick win for richer Telegram messages

---

## Migration Notes

- **ntfy.sh → Telegram (April 2026)** — Notifications migrated from public ntfy.sh topic (`https://ntfy.sh/rsi-alerts`) to private Telegram bot (@LucidLynx1_bot). ntfy code removed in full; Telegram is the sole channel. To add recipients see README §4. Original ntfy topic is now dead — messages expired, no longer published to.

---

## Notes

**On renaming from "RSI Alert Service":** ✅ Done — repo renamed to `market-signals` (April 2026). Local directory `/rsi-alert-service` retained as-is (private, no impact).

**On Claude vs. alternative AI models:** Claude Haiku (~$5/month at current frequency) is the current choice. Alternatives worth evaluating:
- **Gemini Flash (Google)** — cheaper than Haiku, comparable quality for structured tasks, free tier available
- **DeepSeek-R1 / V3** — significantly cheaper, strong reasoning, hosted API available (or self-host via Ollama locally for zero cost)
- **GPT-4o-mini (OpenAI)** — competitive pricing, well-documented
- **Windsurf/Copilot** — IDE-only tools, not suitable for server-side enrichment
- The `ClaudeEnrichmentService` is the only class to change — all others call it via interface. Swapping models is low-effort once a provider is chosen.

*Private Use — Not for Distribution*
