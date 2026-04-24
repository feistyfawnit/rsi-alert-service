# LucidLynx Market Signals — Roadmap

*Live on AWS EC2 (eu-west-1). Phase 1–5 scaffolded March–April 2026. For deeper architecture notes see `architecture.md`; for risk decisions see `risk-register.md`; for full signal-design narrative see `project-log.md`.*

---

## Phase Status

| Phase | Status | Notes |
|-------|--------|-------|
| 1 — Core multi-indicator alerts | ✅ Live | Binance + IG; RSI across 15m/1h/4h; Telegram (ntfy.sh retired Apr 2026). |
| 2 — IG API integration | ✅ Live | Session auto-refresh; DAX / FTSE / S&P / Gold / Oil / Silver seeded. |
| 3 — Claude AI enrichment | ✅ Built, disabled | Set `CLAUDE_API_KEY` + `CLAUDE_ENABLED=true`. ~$5–10/mo. |
| 4 — Semi-automated trading | ✅ Scaffolded, hard-disabled | Requires 3+ months paper-trade validation. Kill switch: `POST /api/trading/kill-switch/activate`. |
| 5 — Anomaly / geopolitical | ⏳ Partial | Volume spike + Polymarket monitor live. Correlation + Uncertainty Mode not started. |

---

## ✅ Completed Milestones (newest first)

| Date | Milestone | Details |
|------|-----------|---------|
| 2026-04-24 | **dipRsiThreshold 60→45 + ADX(14)>20 filter** | P1 fixes from 22-trade analysis. Threshold targets Investopedia-cited "deep pullback in uptrend" zone (40–50). ADX filter suppresses TREND_BUY_DIP during ranging markets (Schwab/Investopedia: ADX<20 = no trend). Deployed together; monitoring 1 week before P2. |
| 2026-04-22 | **Silent signal recording** | Per-instrument `trend-buy-dip-notify` flag — signals log to `position_outcomes` without Telegram alert. S&P 500 TREND_BUY_DIP now silent pending ≥20 trade sample. |
| 2026-04-22 | **Crypto enabled broadly** | BTC + ETH TREND_BUY_DIP re-enabled after R:R drop to 2:1 (previously unreachable at 3:1). |
| 2026-04-22 | **ATR stops + asset-class R:R + R-multiple P&L** | `AtrCalculator` on 15m; stops = ATR × 1.5 (trend) / 2.0 (other). Trend R:R now 2:1 crypto / 3:1 indices. Report €-estimate uses `pnlPct / stopPctAtEntry × riskEur` so 24h auto-closes at +P&L count correctly. Unified crypto exit timeframe to 15m (fixes the SOL 24h auto-close bug where 5m was never polled). |
| 2026-04-22 | **Open positions at top of P&L report** | Same columns as Closed table so rows align visually. Added `Realistic Net` line excluding (symbol, signalType) combos with ≥3 trades and 0 TP hits. |
| 2026-04-22 | **Candle history CSV backup** | `make candles-backup-local` / `make candles-backup-remote` dumps `candle_history` via `\copy` for offline analysis. |
| 2026-04-21 | **BTC re-enabled** | Added back to watchlist for history collection after fixing its data gap. |
| 2026-04-18 | **P&L report with EUR totals + CSV endpoint** | `GET /api/positions/pnl-report/csv`; By-instrument breakdown; Makefile `remote-report` / `remote-csv`. |
| 2026-04-17 | **Duplicate position guard** | `PositionOutcomeService.handleSignalEvent` skips creating a second open position for the same symbol. |
| 2026-04-16 | **Candle history persistence (DB)** | `CandleHistory` entity; ~2,900 candles loaded on startup; RSI accuracy preserved across restarts. |
| 2026-04-14…16 | **Trend Detection v2 + v2.1** | EMA20(1h) primary trend filter + momentum fallback + consecutive-signal fallback. TREND_BUY_DIP / TREND_SELL_RALLY signal types. Stops at half width, trend limits at 3× stop. Suppresses counter-trend signals immediately after warmup. |
| 2026-04-XX | **Deployed to AWS EC2** | Live on `108.128.230.238` (eu-west-1). See `docs/remote-deployment.md`. |
| 2026-04-XX | **ntfy.sh → Telegram migration** | Private bot `@LucidLynx1_bot`; chat-id whitelist in `TELEGRAM_CHAT_IDS`. ntfy code fully removed. |
| 2026-04-XX | **Phase 5 anomaly scaffolding** | `VolumeAnomalyDetector` (4σ), `AnomalyNotificationService`, `PolymarketMonitorService` (5-min polling, ≥8pp odds shift). |
| 2026-04-XX | **Phase 4 auto-trading scaffold** | `IGTradingService` behind kill switch + manual-approval flag + daily loss limit + max-concurrent. Hard-disabled in code. |
| 2026-04-XX | **Phase 3 Claude enrichment built** | `ClaudeEnrichmentService` wired into `NotificationService`; disabled by default. |
| 2026-04-XX | **Phase 2 IG integration** | Live spread-bet auth; 6-hour session refresh; watchlist upsert on every restart via `DataInitializer`. |
| 2026-03–04 | **Phase 1 core engine** | Spring Boot + Postgres + Docker; multi-TF RSI; watchlist CRUD REST; cooldown / quiet-hours; repo renamed `rsi-alert-service → market-signals`. |

---

## 🎯 Active Backlog

### P1 — Do next (1–2 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **Volume confirmation for crypto dips** | ~2h | Require 15m entry-candle volume > 1.2× 20-period mean for crypto TREND_BUY_DIP. IG index volume unreliable — skip for indices. Source: LuxAlgo volume guide + r/algotrading consensus. |
| **Close + Open above Summary** | ~30min | Move the full `All Closed Positions` table to just above Open in the P&L report, so both recent-trade tables are at the top before the Summary / By-Instrument sections. |
| **Periodic candle CSV export + DB cleanup** | ~2h | Extend `PriceHistoryService` trimming: keep last N days in DB, archive older rows to a dated CSV in `reports/candles/` before pruning. Frees EC2 disk without losing history. Alternative: scheduled daily `\copy` via a DB cron. |
| **RSI-bucket outcome analysis** | ~1h SQL | Once ≥2 weeks of `position_outcomes` exist, split TREND_BUY_DIP wins/losses by rsi15m bucket. If <50 fires win materially more, this confirms the RSI-threshold change above. Query in `project-log.md`. |
| **Enable Claude AI enrichment** | ~30min | Set `CLAUDE_API_KEY` + `CLAUDE_ENABLED=true`. Service already built. |
| **Telegram bot commands** | ~3h | `/position`, `/close`, `/status`, `/mute`, `/notrade` — manage service via Telegram. Admin-only via chat-id allowlist. |
| **Momentum fading detector** | ~2h | Notify "FAST TF DIVERGENCE" when 3/3 aligned but fast TFs flip. Exit-timing signal using existing RSI values. |

### P2 — Next sprint (2–4 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **Volatility-spike entry filter** | ~1h | `AtrCalculator.atrExpansionRatio` already built; wire into `SignalDetectionService` to skip signals when 15m ATR > 1.5× its 20-period mean. Flag: `rsi.demo.atr-spike-filter-enabled`. |
| **Volume confirmation for crypto dips** | ~2h | Require entry-candle volume > N× 20-period mean for crypto TREND_BUY_DIP. IG volume unreliable so indices unaffected. |
| **ATR-stops A/B tracking** | ~1h | Persist `stop_basis` on `PositionOutcome`; group expectancy by stop source in the report. Drives the decision to leave ATR on permanently. |
| **Price momentum surge detector** | ~4h | Detect >0.5%/15min or >1%/1h moves *before* RSI aligns. Require 2+ simultaneous indices to filter noise. |
| **Stochastic confirmation layer** | ~3h | Optional %K(14,3,3) confirmation on RSI signals. Proposed logic in `project-log.md`. |
| **Self-service Telegram onboarding** | ~3h | `/start` → admin DM `/approve <id>`; hot-reload approved IDs into `TELEGRAM_CHAT_IDS`. |

### P3 — Speculative (1–3 months)

| Item | Effort | Notes |
|------|--------|-------|
| **Restore 5m crypto exit granularity** | ~1h | If 15m ever misses SOL wicks, add `5m` to SOL `timeframes` and revert `finestExitTimeframe` to the original 5m/15m split. Affects signal alignment counts — review first. |
| **Cross-instrument correlation detector** | ~6h | Fire when 3+ indices align together (DAX + FTSE + S&P all oversold). Part of Phase 5 spec. |
| **High Uncertainty Mode toggle** | ~2h | Suppress all but urgent full-alignment signals during elevated VIX-equivalent. Part of Phase 5 spec. |
| **Phase 4 auto-trading (enable)** | weeks | Only after 3+ months of positive paper P&L. Do not rush. |

---

## Immediate Next Actions

1. **Watch the deploy** — `make ship` then `make remote-report` to confirm Open Positions render at the top with correct unrealized P&L and the `Realistic Net` row appears in Summary.
2. **Paper trade only** — do not enable Phase 4 auto-exec.
3. **Run `make candles-backup-remote` weekly** — local CSV backup in `reports/candles/` (retain until you've validated the DB backup story).
4. **Add `CLAUDE_API_KEY`** when you want richer Telegram context.

---

## Notes

- **Data sources**: Binance (FREE, crypto) and IG (FREE with account, indices/FX/commodities/crypto). Finnhub and Twelve Data rejected — free tiers insufficient for indices coverage and rate limits.
- **Hosting**: AWS EC2 t3.micro (eu-west-1, Free Tier 12 months). Postgres self-hosted in the same Docker Compose. No RDS.
- **AI model swap**: `ClaudeEnrichmentService` is the only file to change. Gemini Flash / DeepSeek / GPT-4o-mini all cheaper than Haiku; swap when ready.

*Private Use — Not for Distribution*
