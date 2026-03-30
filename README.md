# RSI Multi-Timeframe Trading Alert Tool

A production-grade Spring Boot service that monitors financial instruments for RSI alignment signals across multiple timeframes and sends instant push notifications. Monitors crypto (via Binance), indices and commodities (via IG API) in real time.

**Repository:** `https://github.com/feistyfawnit/rsi-alert-service` (Private)

## Features

- ✅ Real-time RSI calculation across multiple timeframes (1m, 5m, 1h, 4h)
- ✅ Multi-instrument support (crypto, indices, commodities, FX)
- ✅ Instant push notifications via ntfy.sh
- ✅ Free-tier market data (Binance for crypto, Finnhub for others)
- ✅ Configurable oversold/overbought thresholds
- ✅ Signal cooldown to prevent spam
- ✅ Quiet hours configuration
- ✅ Full signal history logging
- ✅ REST API for watchlist management

## Tech Stack

- Java 21
- Spring Boot 3.2.3
- PostgreSQL 16
- WebFlux for reactive HTTP calls
- Docker & Docker Compose

## Quick Start

### 1. Prerequisites

- Docker & Docker Compose installed
- (Optional) Paid Finnhub plan for index/commodity data (free tier doesn't include indices)

### 2. Configuration

```bash
# Environment already configured:
# FINNHUB_API_KEY=your_api_key_here  (free tier - indices/stocks require paid plan)
# NTFY_TOPIC=rsi-alerts  ✓ Default
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

### 4. Subscribe to Alerts

**Mobile (iOS/Android):**
1. Install ntfy app from App Store or Play Store
2. Subscribe to topic: `rsi-alerts` (or your custom topic from .env)

**Web:**
- Visit https://ntfy.sh/rsi-alerts in your browser

## API Endpoints

### Instruments Management

```bash
# Get all instruments
GET /api/instruments

# Get enabled instruments only
GET /api/instruments/enabled

# Create new instrument
POST /api/instruments
{
  "symbol": "ETHUSDT",
  "name": "Ethereum",
  "source": "BINANCE",
  "type": "CRYPTO",
  "enabled": true,
  "oversoldThreshold": 30,
  "overboughtThreshold": 70,
  "timeframes": "1m,5m,1h,4h"
}

# Update instrument
PUT /api/instruments/{id}

# Toggle enabled/disabled
PATCH /api/instruments/{id}/toggle

# Delete instrument
DELETE /api/instruments/{id}
```

### Signal History

```bash
# Get all signals
GET /api/signals

# Get signals for specific instrument
GET /api/signals/symbol/SOLUSDT

# Get signals from last N hours
GET /api/signals/recent?hours=24
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

## Adding More Instruments

### For Crypto (FREE - Binance)

```bash
curl -X POST http://localhost:8080/api/instruments \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BNBUSDT",
    "name": "Binance Coin",
    "source": "BINANCE",
    "type": "CRYPTO",
    "enabled": true,
    "timeframes": "15m,1h,4h"
  }'
```

### For Indices/Commodities (IG API — free with account)

Requires `IG_ENABLED=true` and IG credentials in `.env`. Find epic codes at https://labs.ig.com/sample-apps/api-companion/index.html

```bash
curl -X POST http://localhost:8080/api/instruments \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "IX.D.DAX.DAILY.IP",
    "name": "DAX 40",
    "source": "IG",
    "type": "INDEX",
    "enabled": true,
    "timeframes": "15m,1h,4h"
  }'
```

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
    start-hour: 2               # 2 AM
    end-hour: 6                 # 6 AM (no alerts during this window)
```

## Signal Types

- **OVERSOLD (🟢)**: ALL timeframes RSI < 30 → **BUY SIGNAL**
- **OVERBOUGHT (🔴)**: ALL timeframes RSI > 70 → **SELL SIGNAL**  
- **PARTIAL_OVERSOLD (🟡)**: 3 of 4 timeframes oversold → Early warning
- **PARTIAL_OVERBOUGHT (🟠)**: 3 of 4 timeframes overbought → Early warning

## Deployment to Railway (Optional - $5/month)

### 1. Create Railway Account
- Visit https://railway.app
- Connect your GitHub account

### 2. Create New Project
- Click "New Project" → "Deploy from GitHub repo"
- Select `rsi-alert-service` repository

### 3. Add PostgreSQL
- Click "New" → "Database" → "Add PostgreSQL"
- Railway automatically sets DATABASE_URL environment variable

### 4. Configure Environment Variables
- Go to project → Variables tab
- Add:
  - `FINNHUB_API_KEY` (if using Finnhub)
  - `NTFY_TOPIC` (your custom topic name)

### 5. Deploy
- Railway auto-deploys on every git push to main
- Your app will be live at: `https://your-app.up.railway.app`

## Cost Breakdown

| Service | Cost | Notes |
|---------|------|-------|
| **Binance API** | FREE | All crypto data, no key required |
| **Finnhub API** | FREE | 60 calls/min free tier. Indices/stocks require **paid plan** |
| **ntfy.sh** | FREE | Unlimited push notifications |
| **Railway Hosting** | $0-5/month | Free tier: 500 hrs/mo, Hobby: $5 unlimited |
| **PostgreSQL** | Included | Comes with Railway plan |
| **Total** | **$0-5/month** | Fits your budget constraint |

## Monitoring

```bash
# Check if app is running
curl http://localhost:8080/actuator/health

# View recent signals
curl http://localhost:8080/api/signals/recent?hours=1

# View all enabled instruments
curl http://localhost:8080/api/instruments/enabled
```

## Troubleshooting

### No signals appearing?
1. Check logs: `docker-compose logs -f app`
2. Verify instruments are enabled: `GET /api/instruments/enabled`
3. Confirm market is open and prices are moving
4. RSI needs time to build history (~30 minutes after first start)

### Not receiving notifications?
1. Verify ntfy.sh subscription topic matches your `NTFY_TOPIC`
2. Check quiet hours aren't active (2 AM - 6 AM by default)
3. Check signal cooldown (15 minutes between duplicate alerts)

### Database connection errors?
```bash
# Restart PostgreSQL
docker-compose restart postgres

# Check PostgreSQL is healthy
docker-compose ps
```

## Phase Status

- **Phase 1** ✅ — Core RSI alerts, Binance crypto, ntfy.sh notifications
- **Phase 2** ✅ — IG API integration, DAX/FTSE/Gold/Oil/S&P 500 via IG
- **Phase 3** ✅ — Claude AI enrichment built, enable with `CLAUDE_ENABLED=true` + API key
- **Phase 4** ✅ — Auto-execution scaffolded, **hard-disabled** (`TRADING_AUTO_EXECUTION_ENABLED=false`) — do not enable without 3+ months paper trading
- **Phase 5** ⏳ — Volume spike detector live; Polymarket monitor not yet built

See `PHASE2_ROADMAP.md` for full status and next actions.

## Important Notes

⚠️ **This tool is for PERSONAL USE ONLY**  
⚠️ **Not financial advice - use at your own risk**  
⚠️ **MiFID II compliance: no public distribution or commercialization**  
⚠️ **Always verify signals manually before trading**

## Support

For issues or questions, check logs first:
```bash
docker-compose logs -f app
```

---

*March 2026*
