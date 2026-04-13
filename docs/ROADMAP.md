# Phase Completion Status & Next Steps

*All phases 1–4 scaffolded in a single session — March 2026*

---

## ✅ Phase 1 — Core RSI Alert Tool — COMPLETE

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

## Next Actions

1. **Run it** — `docker-compose up -d`, check Telegram (@LucidLynx1_bot), watch signals flow
2. **Paper trade** — log every signal manually for 4–8 weeks before trusting Phase 4
3. **Validate Polymarket slugs** — verify slugs at polymarket.com before deploying (markets expire)
4. **Deploy to Railway** — once paper trading confirms signals are useful
5. **Stochastic confirmation layer** — add %K (14,3,3) as optional confirmation on RSI signals; computable from existing OHLC data, no new API calls. See `PROJECT_LOG.md` for proposed logic.

---

## Migration Notes

- **ntfy.sh → Telegram (April 2026)** — Notifications migrated from public ntfy.sh topic (`https://ntfy.sh/rsi-alerts`) to private Telegram bot (@LucidLynx1_bot). ntfy code removed in full; Telegram is the sole channel. To add recipients see README §4. Original ntfy topic is now dead — messages expired, no longer published to.

---

## Potential Future Work / Backlog

- **Momentum Fading Detector** — When higher timeframes show full RSI alignment (e.g., 3/3 overbought) but lower timeframes (1m–15m) start flipping opposite (oversold), it signals exhaustion of the primary move. This was the exit signal you used today: S&P 3/3 overbought but 1m–15m turning oversold = take profit on short. Formalizing this as a notification ("FAST TF DIVERGENCE — consider taking profit") would provide actionable exit timing without requiring manual chart checks. No new API calls needed — uses in-memory RSI values already calculated.

- **Price Momentum Surge Detector** — Detects rapid price moves (>0.5% in 15min or >1% in 1h) *before* RSI aligns across timeframes. Unlike the volume anomaly detector, this watches percentage change speed, not volume. Key feature: require simultaneous surge across 2+ indices (S&P + DAX) to filter single-market noise. Use case: the April 7 pre-announcement buying (5–6pm UTC) where price surged on moderate volume, well before RSI 3/3 alignment and hours before Trump's 8pm statement. Bidirectional (surge up = possible news leak/institutional flow; surge down = risk-off event). Uses existing 15m candle data — no extra API calls, no 1m TF needed.

*Ivan T & Brian H — Private Use — Not for Distribution*
