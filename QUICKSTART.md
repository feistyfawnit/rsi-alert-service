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

## Budget Confirmation

**Current setup: $0/month** ✅
- Binance API: FREE
- ntfy.sh: FREE  
- Local Docker: FREE

**To deploy 24/7 on Railway: $0-5/month**
- Railway free tier: 500 hours/month (enough for testing)
- Railway Hobby plan: $5/month unlimited (if you need 24/7)

## Optional: Add Finnhub for Indices/Stocks

If you want DAX, FTSE, Gold, Oil (not just crypto):

1. Get free API key: https://finnhub.io/register
2. Edit `.env`: `FINNHUB_API_KEY=your_key_here`
3. Restart: `docker-compose restart app`
4. Add instruments via API (see README.md)

**Finnhub free tier: 60 calls/min - plenty for Phase 1**

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
4. **Phase 2 features** - IG API integration, performance tracking

---

**Your budget constraint ($5/month) is covered.** The free tier setup costs $0, and Railway Hobby plan is exactly $5/month if you need always-on deployment.

Given your situation (potential redundancy, mortgage, kids), I recommend:
- Start with **free local Docker** for testing (1-2 weeks)
- Validate strategy actually works for you
- Only deploy to Railway ($5/month) once proven valuable
- **Do not** rush to Phase 4 auto-trading until 3+ months of manual validation

Stay safe with capital, test thoroughly before risking real money.
