# Quick Start & Demo Guide

## Step 1: Start the Application

```bash
cp .env.example .env          # edit NTFY_TOPIC if you want a private topic
docker-compose up -d
docker-compose logs -f app    # wait for "Started RsiAlertServiceApplication"
```

## Step 2: Subscribe to Notifications

**Phone:** Install **ntfy** app → add topic `rsi-alerts` (or your custom topic from `.env`)  
**Browser:** https://ntfy.sh/rsi-alerts

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
# Lower thresholds so current price crosses — real alert fires on next poll (~30s)
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
| IX.D.DAX.DAILY.IP | DAX 40 | IG API | 15m, 1h, 4h |

IG instruments require `IG_ENABLED=true` and credentials in `.env`. DAX is enabled in config; FTSE/S&P/Gold/Oil are seeded but `enabled: false`.

## Phase Status

- **Phase 1** ✅ Core RSI alerts, Binance crypto, ntfy.sh
- **Phase 2** ✅ IG API integration (DAX, FTSE, Gold, Oil, S&P 500)
- **Phase 3** ✅ Claude AI enrichment — enable with `CLAUDE_ENABLED=true` + API key
- **Phase 4** ✅ Auto-execution scaffolded — **hard-disabled**, do not enable without paper trading
- **Phase 5** ⏳ Volume spike detector live; Polymarket monitor live; cross-instrument correlation not yet built

## Troubleshooting

**App won't start?**
```bash
docker-compose down && docker-compose up -d
docker-compose logs app
```

**No signals after an hour?**
- RSI alignment across 3 timeframes is rare — can take 6–24h
- Check logs for `Updated SOLUSDT` messages to confirm polling is working
- Use `POST /api/test/notify` to confirm ntfy delivery works independently

**Wipe DB and restart fresh:**
```bash
docker-compose down -v && docker-compose up -d
```
