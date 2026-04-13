# LucidLynx Market Signals — Phase Completion Status & Next Steps

*All phases 1–5 scaffolded March–April 2026*

---

## ✅ Phase 1 — Core Multi-Indicator Alert Engine — COMPLETE

- Spring Boot app running in Docker
- Binance API for crypto (SOL, BTC, ETH, BCH) — live
- RSI calculation across 15m / 1h / 4h timeframes
- Push notifications on signal — live (originally ntfy.sh, migrated to Telegram April 2026)
- PostgreSQL watchlist with CRUD REST API
- Configurable thresholds, cooldown, quiet hours

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
| Phase 1 (crypto RSI) | $0 local / $5 Railway | ✅ Ready to deploy |
| Phase 2 (IG indices) | $5 Railway | ✅ Ready — enable IG credentials |
| Phase 3 (Claude AI) | $7-10 | ✅ Ready — add `CLAUDE_API_KEY` |
| Phase 4 (auto-trading) | $5-10 | ⛔ Do not enable without 3+ months paper trading |
| Phase 5 (anomaly) | $0 | ⏳ Volume spike + Polymarket monitor live; cross-correlation not built yet |

---

## Prioritised Backlog

> Timelines assume part-time development (~2–4 hrs/week). P1 = do soon, P2 = next sprint, P3 = future.

### 🔴 P1 — High Value, Do Soon (this week / next 2 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| ~~**Candle History Persistence (DB)**~~ | ~~4h~~ | ~~`PriceHistoryService` is in-memory only — all history lost on every restart. Affects RSI accuracy directly: 4h RSI needs 28 candles = 4.7 days of data; with persistence, RSI improves continuously over weeks. Without it, every restart resets to bare-minimum accuracy. Also eliminates warmup API calls to Binance on boot. Add `CandleHistory` JPA entity, persist on receipt, load on startup.~~ **COMPLETE — persists to PostgreSQL, loads on startup, eliminates warmup calls.** |
| **Telegram Bot Commands** | ~3h | Manage the service via Telegram messages instead of curl/API. Commands: `/position BTCUSDT` (set active position → enables anomaly monitoring), `/close` (clear position → disables anomaly monitoring), `/status` (show position, no-trade mode, muted symbols), `/mute BTCUSDT` / `/unmute BTCUSDT`, `/notrade on` / `/notrade off`. Requires a Telegram webhook or polling listener (`TelegramCommandService`) that parses incoming messages and calls `AppSettingsService`. Admin-only — restrict to known chat IDs. |
| **Momentum Fading Detector** | ~2h | Notify "FAST TF DIVERGENCE — consider taking profit" when 3/3 aligned but fast TFs (1m–15m) flip opposite. No new API calls. Uses existing RSI values. Exit timing signal — replaces manual chart check. |
| **Deploy to Railway** | ~1h | For 24/7 uptime — especially important once candle history is persisted (no more warmup on laptop wake/restart). |
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

1. ✅ App is running — `make logs` to watch live
2. **Paper trade** — log every signal manually for 4–8 weeks before trusting Phase 4
3. **Deploy to Railway** — for 24/7 uptime (P1 above)
4. **Add `CLAUDE_API_KEY`** — quick win for richer Telegram messages

---

## Migration Notes

- **ntfy.sh → Telegram (April 2026)** — Notifications migrated from public ntfy.sh topic (`https://ntfy.sh/rsi-alerts`) to private Telegram bot (@LucidLynx1_bot). ntfy code removed in full; Telegram is the sole channel. To add recipients see README §4. Original ntfy topic is now dead — messages expired, no longer published to.

---

## Notes

**On renaming from "RSI Alert Service":** The tool now incorporates stochastic confirmation, Polymarket geopolitical odds, volume anomaly detection, and Claude AI enrichment — RSI is one of many signals, not the only one. Consider renaming to `market-alert-service` or `signal-alert-service` when deploying to Railway. No urgency — it's a private repo and the name doesn't affect functionality.

**On Claude vs. alternative AI models:** Claude Haiku (~$5/month at current frequency) is the current choice. Alternatives worth evaluating:
- **Gemini Flash (Google)** — cheaper than Haiku, comparable quality for structured tasks, free tier available
- **DeepSeek-R1 / V3** — significantly cheaper, strong reasoning, hosted API available (or self-host via Ollama locally for zero cost)
- **GPT-4o-mini (OpenAI)** — competitive pricing, well-documented
- **Windsurf/Copilot** — IDE-only tools, not suitable for server-side enrichment
- The `ClaudeEnrichmentService` is the only class to change — all others call it via interface. Swapping models is low-effort once a provider is chosen.

*Ivan T & Brian H — Private Use — Not for Distribution*
