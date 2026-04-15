# AGENTS.md

## Project Purpose

`rsi-alert-service` is a private Spring Boot market-monitoring service.
It polls Binance and IG market data, calculates RSI and Stochastic across configured timeframes, detects signals, logs them, and sends Telegram alerts.

## Start Here

If you are reviewing or changing this repo, read in this order:

1. `README.md` — human-friendly overview and doc map
2. `src/main/resources/application.yml` — current runtime configuration and enabled instruments
3. `docs/troubleshooting.md` — operational checks, IG quota limits, common failures
4. `docs/project-log.md` — important incidents, decisions, and historical context
5. `docs/risk-register.md` — operational constraints and open risks

## Current Runtime Shape

- **Binance instruments**: `SOLUSDT`, `BTCUSDT`, `ETHUSDT`, `BCHUSDT`
  - Timeframes: `15m,1h,4h`
- **IG indices**: DAX, FTSE 100, S&P 500, Nasdaq 100
  - Timeframes: `15m,30m,1h`
- **IG commodities**: Gold, Silver, Oil (Brent)
  - Timeframes: `15m,1h,4h`

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

## Useful Commands

```bash
make up
make logs
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/instruments/enabled
curl http://localhost:8080/api/signals/rsi-snapshot
```
