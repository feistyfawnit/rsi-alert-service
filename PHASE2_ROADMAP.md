# Phase 2+ Roadmap

## Immediate Next Steps (When Ready)

### Phase 1.5: Volume Spike Detection (Your Addition)

Based on your requirement for detecting anomalous volume (TACO trade pattern):

**Implementation Plan:**
1. **Volume Monitor Service** - Track rolling volume baselines
2. **Polymarket API Client** - Monitor geopolitical prediction market odds
3. **Anomaly Alert System** - High-priority alerts for volume spikes
4. **Trading Pause Mechanism** - Auto-pause on detected anomalies

**Estimated Time:** 4-6 hours  
**Cost:** $0 (Polymarket API is free)  
**Priority:** Medium - add after validating core RSI signals work

**Data Sources:**
- Binance volume data (already available, free)
- Polymarket public API (free, no auth required)
- Could add Kalshi API (also free)

**Why defer to Phase 1.5:**
- Core RSI signals need validation first
- Volume spike detection is defensive, not primary trading logic
- You need to see if Brian's strategy works before adding complexity

---

## Phase 2: Full Production Features (Weekends 2-3)

### A. IG API Integration
- OAuth2 authentication with token refresh
- Read positions and account balance
- Historical data fetch for backtesting
- Rate limit handling (60/min non-trading, 30/min per account)

### B. Database Watchlist Management
- Migrate from YAML config to PostgreSQL
- Full CRUD via REST API
- Per-instrument threshold overrides
- Enable/disable individual instruments

### C. Performance Tracking
- Signal outcome logging (did price move as expected?)
- Win rate calculation per instrument
- P&L tracking if trades placed manually
- Monthly performance reports

### D. Email Backup Alerts
- Spring Mail integration
- Gmail SMTP configuration
- Fallback if ntfy.sh fails

**Estimated Time:** 8-10 hours  
**Cost:** Still $0-5/month (no additional costs)

---

## Phase 3: AI Enhancement (Weekend 4)

### A. Claude API Integration
- News summarization per signal
- Signal commentary in plain English
- Confidence scoring based on news sentiment

### B. Advanced Analytics
- Historical backtesting results
- Correlation detection (BTC vs SOL, etc.)
- Pattern recognition over time

**Estimated Time:** 4-6 hours  
**Cost:** $2-5/month for Claude API (based on usage)  
**Total budget: $7-10/month** - slightly over your $5 constraint

**Recommendation:** Skip Claude initially, add only if RSI proves profitable enough to justify cost

---

## Phase 4: Semi-Automated Trading (DO NOT START FOR 3+ MONTHS)

⚠️ **HIGH RISK - EXTENSIVE TESTING REQUIRED** ⚠️

### Prerequisites Before Starting:
- [ ] 3+ months of manual signal validation
- [ ] Proven positive P&L on paper trading
- [ ] Emergency fund in place (given your financial situation)
- [ ] Risk management rules defined and tested
- [ ] IG demo account testing completed (1 month minimum)

### Features:
- IG deal placement API
- Position sizing algorithm (configurable % of capital)
- Stop-loss placement (ATR-based or fixed %)
- Take-profit targets (RSI reversal or fixed %)
- Manual approval step (Telegram bot?)
- Kill switch (emergency stop all trading)
- Max drawdown limits
- Daily loss limits

### Risk Management (MANDATORY):
- **Max position size:** 5-10% of capital per trade
- **Max concurrent positions:** 2-3 instruments
- **Daily loss limit:** 2% of account balance
- **Max drawdown:** -5% triggers full trading pause
- **Manual approval required** for every auto-trade initially

**Estimated Time:** 6-8 hours to build, 1+ month to test  
**Cost:** Same $5-10/month  
**Risk:** **VERY HIGH** - you could lose significant capital

**Given Your Situation:**
- You mentioned potential redundancy
- You have a mortgage and kids
- **Strong recommendation:** Do NOT enable auto-trading until:
  1. Financial situation is stable
  2. Strategy proven over 6+ months
  3. You have 6-12 months emergency fund
  4. You're only risking capital you can afford to lose entirely

---

## Alternative: Conservative Approach

If your financial situation is uncertain, consider:

**Phase 2 Lite (Budget-Conscious):**
1. Keep free Binance data source only (crypto)
2. Skip Finnhub (indices/stocks) - saves API complexity
3. Skip Claude AI - saves $2-5/month
4. Run on local Docker (free) instead of Railway ($5/month)
5. Use phone/laptop as server when you're actively trading
6. **Never** enable auto-trading

**This keeps costs at $0/month** while still getting value from RSI signals.

**Trade manually** based on alerts:
- You receive push notification
- You review the signal
- You decide whether to trade
- You place trade manually via IG platform
- Zero automation risk

---

## Cost-Benefit Analysis

**Your stated constraints:**
- Budget: $5/month maximum
- Risk: High (potential redundancy, mortgage, dependents)

**Recommendation:**

| Phase | Cost | Deploy? | Rationale |
|-------|------|---------|-----------|
| Phase 1 (current) | $0 | ✅ YES | Test locally, no cost, validate concept |
| Volume Detection | $0 | ⚠️ MAYBE | Wait until RSI proven useful first |
| Phase 2 | $5/month | ⚠️ ONLY IF VALIDATED | Deploy to Railway only if Phase 1 shows value after 2-4 weeks |
| Phase 3 AI | $7-10/month | ❌ NO | Over budget, low priority |
| Phase 4 Auto-Trading | $5-10/month | ❌ ABSOLUTELY NOT | Far too risky given your financial situation |

---

## Recommended Timeline for You

**Week 1-2: Validate Core Concept**
- Run Phase 1 locally (free)
- Monitor Solana, BTC, ETH signals
- Track signals manually in spreadsheet
- Paper trade - pretend to take positions based on signals
- Calculate theoretical P&L

**Week 3-4: Decide Next Steps**

If signals are profitable in paper trading:
- Deploy to Railway ($5/month) for 24/7 monitoring
- Add more crypto instruments
- Continue paper trading

If signals are NOT profitable:
- **STOP HERE** - save your $5/month
- Strategy doesn't work, avoid losing real money
- Document findings, move on

**Month 2-3: Real Trading (Manual Only)**

If Phase 1 showed consistent positive paper results:
- Start manual trading with **SMALL** positions (1-2% of capital)
- Use IG demo account first
- Only graduate to live account after demo success

**Month 4+: Consider Enhancements**

Only if you've made consistent profit manually:
- Add volume spike detection (Phase 1.5)
- Consider IG API for position tracking (Phase 2)
- **Still NO auto-trading**

**Month 12+: Auto-Trading Consideration**

Only if:
- You've been profitable for 12 months
- Your job situation is stable
- You have emergency fund
- You're only automating with money you can lose

---

## Final Advice

Given you mentioned:
- "Talk of redundancy where I am"
- "Big mortgage"
- "Single with kids"

**My honest recommendation:**

1. **Test Phase 1 for free** - it's built, costs nothing, see if it helps
2. **Paper trade only** for at least 1-2 months
3. **Only deploy if proven valuable** - don't spend $5/month until you're confident
4. **Never auto-trade** - too risky given your situation
5. **Focus on your day job** - this is a side tool, not a replacement income
6. **Build emergency fund first** - before risking capital on trading

Brian's strategy achieved 18% returns, but:
- That was manual trading with human judgment
- Past performance doesn't guarantee future results  
- He may have had losses you don't know about
- Your risk tolerance should be near-zero right now

**Use this tool as alerts only. Trade manually. Risk small amounts. Protect your family first.**

---

*Built with concern for your wellbeing - March 2026*
