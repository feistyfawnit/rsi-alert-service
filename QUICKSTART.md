# Quick Start & Demo Guide — LucidLynx Market Signals

> **Prerequisite (macOS with Colima):** Start Docker runtime first  
> `colima start`

## Step 1: Start the Application

```bash
cp .env.example .env          # edit TELEGRAM_CHAT_IDS for private notifications
make up                       # starts Colima if needed, builds & runs app
```

## Step 2: Subscribe to Notifications

**For recipients:** Message **@LucidLynx1_bot** (the B&I Alert Bot) and send `/start`, then tell the admin you've done so.

**For admin:** After they message the bot, get their chat ID from `getUpdates` and add to `TELEGRAM_CHAT_IDS` in `.env` (comma-separated)

## Step 3: Verify It's Working

```bash
# Instruments currently being monitored
curl http://localhost:8080/api/instruments/enabled

# Signals logged so far
curl http://localhost:8080/api/signals/recent?hours=24
```

---

## Demo: Triggering Notifications Without Waiting

### 1. RSI signal alert (labelled [TEST], fires immediately)

```bash
curl -X POST http://localhost:8080/api/test/notify
```

Sends a synthetic OVERSOLD signal for Solana. Shows title, RSI values, signal strength.

### 2. Volume spike anomaly alert

```bash
curl -X POST "http://localhost:8080/api/test/anomaly?type=volume"
```

Fires a `⚠️ ANOMALY DETECTED` notification for a 4.8σ volume spike on SOL 15m.

### 3. Polymarket odds shift anomaly alert

```bash
curl -X POST "http://localhost:8080/api/test/anomaly?type=polymarket"
```

Fires a `⚠️ ANOMALY DETECTED` notification for a 12.5pp odds shift on the US tariff market.

### 4. Real RSI signal (uses live market data, not labelled [TEST])

```bash
# Lower thresholds so current price crosses — real alert fires on next poll
# (~5 min for Binance instruments, ~15 min for IG instruments)
curl -X POST "http://localhost:8080/api/test/lower-thresholds?oversold=50&overbought=50"

# Reset when done
curl -X POST http://localhost:8080/api/test/reset-thresholds
```

⚠️ These fire real notifications without a [TEST] label. Reset thresholds immediately after.

---

## Active Instruments

| Symbol | Name | Source | Timeframes |
|--------|------|--------|------------|
| SOLUSDT | Solana | Binance (free) | 15m, 1h, 4h |
| BTCUSDT | Bitcoin | Binance (free) | 15m, 1h, 4h |
| ETHUSDT | Ethereum | Binance (free) | 15m, 1h, 4h |
| BCHUSDT | Bitcoin Cash | Binance (free) | 15m, 1h, 4h |
| IX.D.DAX.DAILY.IP | DAX 40 | IG API | 15m, 30m, 1h |
| IX.D.FTSE.DAILY.IP | FTSE 100 | IG API | 15m, 30m, 1h |
| IX.D.SPTRD.DAILY.IP | S&P 500 | IG API | 15m, 30m, 1h |
| IX.D.NASDAQ.CASH.IP | Nasdaq 100 | IG API | 15m, 30m, 1h |
| CS.D.USCGC.TODAY.IP | Gold (Spot) | IG API | 15m, 1h, 4h |
| CS.D.USCSI.TODAY.IP | Silver | IG API | 15m, 1h, 4h |
| CC.D.LCO.USS.IP | Oil (Brent) | IG API | 15m, 1h, 4h |

IG instruments require `IG_ENABLED=true` and credentials in `.env`.

## Phase Status

- **Phase 1** ✅ Core RSI alerts, Binance crypto, Telegram notifications
- **Phase 2** ✅ IG API integration (indices + commodities)
- **Phase 3** ✅ Claude AI enrichment — enable with `CLAUDE_ENABLED=true` + API key
- **Phase 4** ✅ Auto-execution scaffolded — **hard-disabled**, do not enable without paper trading
- **Phase 5** ⏳ Volume spike detector live; Polymarket monitor live; cross-instrument correlation not yet built

## Troubleshooting

For operational issues, log errors, and IG quota guidance, see:

- `docs/TROUBLESHOOTING.md`
- `AGENTS.md`
