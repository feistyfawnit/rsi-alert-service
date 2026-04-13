# RSI Multi-Timeframe Trading Alert Tool

A production-grade Spring Boot service that monitors financial instruments for RSI alignment signals across multiple timeframes and sends instant push notifications. Monitors crypto (via Binance), indices and commodities (via IG API) in real time.

**Repository:** `https://github.com/feistyfawnit/rsi-alert-service` (Private)

## Features

- ✅ Real-time RSI calculation across multiple timeframes (15m, 30m, 1h, 4h — configurable per instrument)
- ✅ Multi-instrument support (crypto via Binance, indices/commodities via IG API)
- ✅ Three-tier signal hierarchy: FULL → PARTIAL → WATCH
- ✅ Private push notifications via Telegram bot (multi-recipient, no app install needed)
- ✅ Partial signal monitoring with lagging-TF follow-ups
- ✅ No-trade mode + per-symbol muting (persistent across restarts via DB)
- ✅ Active position tracking — gates anomaly alerts until a trade is open
- ✅ Volume anomaly detection (σ-based) + Polymarket geopolitical odds monitor
- ✅ Claude AI signal enrichment (optional)
- ✅ Signal CSV archival with outcome backfill (1h/4h/24h price tracking)
- ✅ REST API for instruments, signals, settings, retrospective analysis
- ✅ Auto-trading scaffolded (Phase 4 — hard-disabled, requires demo validation)

## Tech Stack

- Java 25
- Spring Boot 3.5.3
- PostgreSQL 16
- WebFlux for reactive HTTP calls
- Docker & Docker Compose

## Quick Start

### 1. Prerequisites

- Docker & Docker Compose installed
- (Optional) Paid Finnhub plan for index/commodity data (free tier doesn't include indices)

### 2. Configuration

Copy `.env.example` to `.env` and fill in your values. See comments in `.env.example` for each variable.

Key settings for notifications:
```bash
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_bot_token_from_botfather
TELEGRAM_CHAT_IDS=your_chat_id          # comma-separate for multiple recipients
```

### 3. Run with Docker Compose

```bash
# Build and start services
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop services
docker-compose down
```

The app will be available at `http://localhost:8080`

### 4. Receive Alerts via Telegram

Alerts are delivered privately via the **B&I Alert Bot** (@LucidLynx1_bot) on Telegram.

**To add a new recipient:**
1. Open Telegram and search **@LucidLynx1_bot** — tap **START**
2. Get your chat ID:
   ```bash
   curl https://api.telegram.org/bot{TOKEN}/getUpdates
   # Look for "chat":{"id":XXXXXXX} in the response
   ```
3. Add your ID to `TELEGRAM_CHAT_IDS` in `.env` (comma-separated for multiple):
   ```
   TELEGRAM_CHAT_IDS=6633143916,987654321
   ```
4. Rebuild: `docker-compose up -d --build`

## API Endpoints

Full reference: **[docs/api.md](docs/api.md)**

Key endpoints:

```bash
GET  /api/instruments/enabled          # Active instruments
GET  /api/signals/recent?hours=24      # Recent signals
GET  /api/signals/rsi-snapshot          # Live RSI values
POST /api/signals/no-trade-mode/on     # Suppress PARTIAL/WATCH
POST /api/trading/kill-switch/activate  # Emergency stop
POST /api/test/notify                   # Fire test notification
```

## Pre-Configured Instruments

The app comes pre-configured with:
- **Solana (SOLUSDT)** - Binance
- **Bitcoin (BTCUSDT)** - Binance
- **Ethereum (ETHUSDT)** - Binance
- **Bitcoin Cash (BCHUSDT)** - Binance
- **DAX 40** - IG API (requires `IG_ENABLED=true` + credentials)
- **FTSE 100, S&P 500, Gold, Oil** - IG API (seeded, set `enabled: true` in YAML)

All crypto data is FREE via Binance API (no API key required). Indices/commodities require an IG account.

## Configuration

Edit `src/main/resources/application.yml` or use environment variables:

```yaml
rsi:
  period: 14                    # RSI calculation period
  oversold-threshold: 30        # Default oversold threshold
  overbought-threshold: 70      # Default overbought threshold
  polling:
    interval-seconds: 60        # How often to check for signals
  quiet-hours:
    enabled: true
    start-hour: 22              # 10 PM UTC (11 PM BST)
    end-hour: 8                 # 8 AM UTC (9 AM BST) — full signals bypass quiet hours
```

See [docs/api.md](docs/api.md) for adding instruments via the REST API.

## Signal Types

| Priority | Signal | Condition | ntfy priority |
|---|---|---|---|
| 1 | 🟢 OVERSOLD / 🔴 OVERBOUGHT | All TFs aligned | urgent (5) — bypasses DND |
| 2 | 🟡 PARTIAL | All but 1 TF aligned | default (3) |
| 3 | 👀 WATCH | 1 TF crossed + others approaching | low (2) |

## Trade Duration & IG Financing Costs

IG Daily Funded Bets (DFB) accrue **overnight financing charges every calendar day** the position is held, regardless of whether markets are open. Typical cost on a crypto position:

- Daily Admin Fee: ~€0.14/day
- Daily Financing Adjustment: ~€0.38/day
- **Total: ~€0.50–0.55/day per standard position**

> **Target in-and-out within 1–4 days.** If a trade hasn't moved in your favour within 3–4 days, the carry cost alone is a valid reason to close — regardless of RSI. A week's hold costs ~€3.50+ before spread is considered.

## Troubleshooting

See [QUICKSTART.md](QUICKSTART.md) for detailed troubleshooting. Quick checks:

```bash
curl http://localhost:8080/actuator/health           # App running?
curl http://localhost:8080/api/instruments/enabled     # Instruments active?
curl http://localhost:8080/api/signals/recent?hours=1  # Any signals?
docker-compose logs -f app                             # Check logs
```

## Documentation

| Doc | Purpose |
|---|---|
| [docs/architecture.md](docs/architecture.md) | System design, components, data flow |
| [docs/api.md](docs/api.md) | Full REST API reference |
| [QUICKSTART.md](QUICKSTART.md) | Setup, demo, troubleshooting |

⚠️ **Personal use only — not financial advice — MiFID II: no public distribution**

---

*April 2026*
