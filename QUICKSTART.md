# Quick Start Guide - Get Running in 5 Minutes

## Step 1: Start the Application (2 minutes)

```bash
cd /Users/terryi/Windsurf/rsi-alert-service

# Copy environment file (optional - works without Finnhub key for crypto only)
cp .env.example .env

# Start everything with Docker
docker-compose up -d

# Wait 30 seconds for startup, then check logs
docker-compose logs -f app
```

You should see logs showing:
- ✅ Connected to PostgreSQL
- ✅ Application started on port 8080
- ✅ Polling market data for Solana, Bitcoin, Ethereum

## Step 2: Subscribe to Notifications (1 minute)

**On your phone:**
1. Install **ntfy** app (App Store or Play Store)
2. Tap **+** to add topic
3. Enter: `rsi-alerts`
4. Done! You'll receive push notifications

**On computer:**
- Open browser to: https://ntfy.sh/rsi-alerts

## Step 3: Wait for First Signal (~30 minutes)

The app needs to build RSI history (28 candles minimum). First signals typically appear within 30-60 minutes depending on market volatility.

**What you'll see:**
- 🟢 **OVERSOLD** - All 4 timeframes RSI < 30 → Consider LONG
- 🔴 **OVERBOUGHT** - All 4 timeframes RSI > 70 → Consider SHORT/EXIT
- 🟡 **Partial signals** - 3 of 4 aligned → Early warning

## Step 4: Verify It's Working

```bash
# Check enabled instruments
curl http://localhost:8080/api/instruments/enabled

# Check recent signals (after 30+ mins)
curl http://localhost:8080/api/signals/recent?hours=24

# View live logs
docker-compose logs -f app
```

## Budget & Deployment

**Free local setup:** $0/month
- Binance API: FREE
- ntfy.sh: FREE
- Local Docker: FREE

**Cloud deployment (Railway):** $5/month for 24/7 always-on monitoring

## Optional: Add More Data Sources

**For indices (DAX, FTSE) and commodities (Gold, Oil):**

1. Open IG demo account at https://www.ig.com
2. Generate API key at https://www.ig.com/myig/settings/api-keys
3. Update `.env` with IG credentials (see `.env.example`)
4. Enable instruments in `application.yml`
5. Restart: `docker-compose restart app`

## Troubleshooting

**App won't start?**
```bash
docker-compose down
docker-compose up -d
docker-compose logs app
```

**No signals after 1 hour?**
- Check crypto markets are moving (they trade 24/7)
- Verify logs show "Updated SOLUSDT" messages
- RSI alignment is rare - you might wait 6-24 hours for first full signal

**Need to stop everything?**
```bash
docker-compose down
```

**Need to wipe database and restart fresh?**
```bash
docker-compose down -v
docker-compose up -d
```

## Next Steps After First Signal

1. **Customize thresholds** - Edit instrument settings via API
2. **Add more instruments** - See README.md API examples
3. **Deploy to Railway** - For 24/7 monitoring
4. **Advanced features** - Claude AI enrichment, IG API for indices, trading integration (see README.md)
