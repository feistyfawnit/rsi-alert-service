# AGENTS.md

## Project Purpose

`market-signals` is a private Spring Boot market-monitoring service.
It polls Binance and IG market data, calculates RSI and Stochastic across configured timeframes, detects signals, logs them, and sends Telegram alerts.

## Start Here

If you are reviewing or changing this repo, read in this order:

1. `README.md` — human-friendly overview and doc map
2. `src/main/resources/application.yml` — current runtime configuration and enabled instruments
3. `docs/troubleshooting.md` — operational checks, IG quota limits, common failures
4. `docs/project-log.md` — important incidents, decisions, and historical context
5. `docs/risk-register.md` — operational constraints and open risks

## Current Runtime Shape

- **Binance instruments**: `SOLUSDT` (full signals), `BTCUSDT` (enabled, TREND_BUY_DIP suppressed — history collection only), `ETHUSDT`/`BCHUSDT` (disabled)
  - Timeframes: `15m,1h,4h`
- **IG indices**: DAX, S&P 500 (full signals); FTSE 100 (enabled, TREND_BUY_DIP suppressed); Nasdaq 100 (disabled)
  - Timeframes: `15m,30m,1h`
- **IG commodities**: Gold (enabled, TREND_BUY_DIP suppressed); Silver, Oil (disabled)
  - Timeframes: `15m,1h,4h`
- **`trend-buy-dip-enabled` flag**: per-instrument in YAML, synced to DB on restart. FTSE/Gold/Silver = false (backtest −0.86R to −1.00R). YAML wins for this field (unlike `enabled` which DB preserves).

## Important Guardrails

- **IG historical data allowance is the main constraint**: 10,000 data points/week
- Current enabled IG set is designed to stay around **~5,300 points/week**
- **Do not** add IG instruments, change IG polling frequency, or trigger repeated warmups without recalculating budget
- **Do not** trust a new IG epic code until it is verified with:
  - IG market search
  - a direct one-candle `/prices/.../1` curl test
- `application.yml` seeds instruments, but stale DB rows can still exist after epic changes; restarting and checking enabled instruments/logs matters

## Canonical Truth vs Historical Docs

- **Current truth**:
  - `application.yml`
  - `README.md`
  - `docs/troubleshooting.md`
- **Historical/reference docs**:
  - `docs/project-log.md` — incident and decision history
  - `docs/archived/requirements.md` — original project specification; useful context, but not the current source of truth
  - `docs/roadmap.md` — backlog and phase tracking

## Deployment

Primary path: **AWS Free Tier** — Dublin region (`eu-west-1`), 12 months free, ~€15/month after.
See `docs/deployment-aws.md` for full guide (Terraform optional).

Alternatives in `docs/archived/`: Alibaba Cloud (simpler, cheaper post-free-tier), Oracle Cloud (free forever but complex).

## Useful Commands

```bash
# Local (Mac / Colima)
make up
make logs
make pnl-report

# AWS EC2 (run from Mac — SSH in automatically)
make deploy        # pull + rebuild + start on EC2
make remote-logs   # tail logs from EC2
make ship          # deploy then tail (one command)

# Health checks (swap localhost for EC2 IP when targeting AWS)
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/instruments/enabled
curl http://localhost:8080/api/signals/rsi-snapshot
curl http://localhost:8080/api/positions/pnl-summary
```
