# LucidLynx Market Signals — Requirements Specification
**Multi-indicator trading alert platform**  
Private Use Only | March–April 2026

---

## 1. Project Overview

Private Spring Boot service automating multi-indicator market scanning for Ivan and Brian. Monitors indices, crypto, FX, and commodities; detects RSI alignment signals across timeframes; sends instant push notifications — faster than manual scanning, without fatigue.

- Target: ~18–20% p.a., retaining ~75–80% capital reserve
- Strategy: RSI alignment across 4hr, 1hr, 5min, 1min — all oversold (<30) = buy, all overbought (>70) = sell/exit
- Proved manually: Solana caught RSI oversold ~$78, exited ~$89 overbought
- Instruments: DAX, FTSE 100, US 500, Solana, BTC, BCH, Gold, Oil, Iron Ore — any IG-available instrument
- Private use only — not for distribution (MiFID II)

---

## 3. Core Features

### 3.1 RSI Signal Engine

- Calculate RSI (14-period default, configurable) across multiple timeframes simultaneously
- Configurable timeframes: 10s, 1min, 3min, 5min, 10min, 1hr, 4hr, daily
- Oversold threshold: configurable, default RSI < 30
- Overbought threshold: configurable, default RSI > 70
- **Full alignment signal**: ALL configured timeframes breach threshold simultaneously
- **Partial alignment**: configurable minimum (e.g. 3 of 4 timeframes) for early warning alerts
- Signal strength scoring — how far below/above threshold each timeframe is

### 3.2 Market Data Integration

- **Primary**: IG REST API (labs.ig.com) — live prices, positions, account data
  - Demo account support for testing (Ivan's demo: Z68KTE spread bet, Z68KTF options)
  - Live account switching when ready
- **Secondary/fallback**: Finnhub API or Binance API (free tier) for crypto RSI data
- Instruments configurable via watchlist stored in database:
  - Indices: DAX, FTSE 100, US 500, US Tech 100, Wall Street, EU Stocks 50
  - Crypto: Solana, Bitcoin, Ethereum, Bitcoin Cash
  - Commodities: Gold, Silver, Oil (US Crude), Natural Gas, Iron Ore, Copper
  - FX: EUR/USD, GBP/USD and others as needed

### 3.3 Notification System

- **Push**: ntfy.sh (simplest — HTTP POST, free, no app required, just subscribe to a topic)
- **Email**: backup alerts via Spring Mail (Gmail SMTP or similar)
- Notification content: instrument name, signal type (oversold/overbought), RSI value per timeframe, current price, suggested action
- Configurable quiet hours (e.g. no alerts 2am–6am)
- Rate limiting — don't spam if signal persists across multiple polling cycles

### 3.4 IG API Integration

- Authentication: OAuth2 / IG API key management
- Read positions: fetch open positions, P&L, account balance
- Place deals: open spread bet or barrier option positions programmatically
- Close positions: exit trades automatically or on manual trigger
- **Phase 1**: notify only — Ivan/Brian place trades manually
- **Phase 2**: optional auto-execute on confirmed signal with configurable max stake

### 3.5 CRUD Management API

- REST endpoints to manage watchlist (add/remove/update instruments)
- Configure RSI thresholds, timeframes, alert preferences per instrument
- View signal history and performance log
- Enable/disable individual instruments or entire watchlist
- Secured endpoints (Basic Auth or API key to start)

### 3.6 Signal Performance Tracking

- Log every signal: timestamp, instrument, RSI values, price at signal
- Log outcome — did price move as expected after signal?
- Basic P&L tracking if trades placed via IG API
- Monthly performance report — basis for evaluating strategy and future productisation

---

## 4. Technical Stack & Architecture

### 4.1 Recommended Stack

| Layer | Recommendation |
|---|---|
| Framework | Spring Boot 3.x |
| Language | Java 21 (LTS) |
| Database | PostgreSQL |
| Scheduling | Spring `@Scheduled` or Quartz for complex schedules |
| HTTP Client | Spring WebClient (reactive) for IG API & data feeds |
| Notifications | ntfy.sh (HTTP POST) + Spring Mail |
| Build | Maven or Gradle |
| Containerisation | Docker + docker-compose for local dev |
| Hosting | Railway.app (recommended) or Render.com |
| Alternative Hosting | AWS EC2 t2.micro (free tier 12 months) if more control needed |
| CI/CD | GitHub Actions (GHEC) |
| IaC | Terraform (TFE) if AWS route chosen |
| AI/LLM | Claude API (Anthropic) for news summarisation and signal commentary |

### 4.2 Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                  Spring Boot App                     │
│                                                     │
│  ┌─────────────┐    ┌──────────────┐               │
│  │  Scheduled  │───▶│ RSI Calculator│               │
│  │  Poller     │    │  (per TF)    │               │
│  └─────────────┘    └──────┬───────┘               │
│                            │                        │
│                     ┌──────▼───────┐               │
│                     │Signal Detector│               │
│                     └──────┬───────┘               │
│                            │ ApplicationEvent       │
│          ┌─────────────────┼──────────────┐        │
│          ▼                 ▼              ▼        │
│  ┌──────────────┐  ┌────────────┐  ┌──────────┐  │
│  │Notification  │  │IG API      │  │ Signal   │  │
│  │Service       │  │Client      │  │ Logger   │  │
│  │(ntfy/email)  │  │(positions) │  │(Postgres)│  │
│  └──────────────┘  └────────────┘  └──────────┘  │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │         REST API (CRUD / Config)            │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
         │                          │
    ┌────▼─────┐             ┌──────▼──────┐
    │IG REST   │             │ Finnhub /   │
    │API       │             │ Binance API │
    └──────────┘             └─────────────┘
```

### 4.3 Hosting Recommendation

**Railway.app** is the recommended starting point:
- Connect GitHub repo → auto-deploys on push
- Built-in PostgreSQL add-on — no separate database setup
- Always-on (unlike Render free tier which sleeps)
- ~€5–10/month after free tier — negligible for a personal tool
- No DevOps overhead — focus on trading logic not server management
- Easy environment variables for API keys and secrets

Migrate to AWS/k8s later if scale or control requires it.

---

## 5. AI / Agentic Features

- **News summarisation**: before alerting, call Claude API to fetch and summarise recent news for the instrument
- **Signal commentary**: AI explains why the signal is occurring in plain English
- **Significance filter**: AI decides whether news is material enough to modify signal priority
- Example output: *"Solana RSI oversold across all timeframes. Recent news: Alpenglow upgrade confirmed for Q1 2026. Signal confidence: HIGH"*
- Future: agentic loop — AI monitors, decides, notifies, and optionally executes with human approval step

---

## 6. Brian's Strategy — Encoded Rules

The following rules are based on Brian's manually applied strategy. All thresholds should be configurable:

- **BUY SIGNAL**: RSI < 30 on 4hr, 1hr, 5min, and 1min simultaneously
- **Daily (1D) timeframe**: use as a *filter* (e.g. only take buy signals if daily RSI < 50), not as a hard alignment requirement — requiring daily RSI < 30 simultaneously would reduce signals to once a month or less
- **SELL SIGNAL**: RSI > 70 on 4hr, 1hr, 5min, and 1min simultaneously
- **Partial signal** (3 of 4 timeframes): early warning notification only, not a trade signal
- **Capital reserve rule**: never deploy more than 25–40% of available capital on open positions
- Higher beta preference for longs: Solana preferred over Bitcoin Cash for upside capture
- Avoid new positions Friday afternoon — weekend gap risk
- Avoid new positions immediately after major macro news — wait for dust to settle
- Short signals valid too — overbought RSI alignment can trigger short/put signals

### 6.1 Brian Also Uses Stochastic (March 2026 observation)

Brian was observed using the **Stochastic oscillator** (14, 3, 3) on the IG platform alongside RSI — specifically on **Germany 40 (DAX)** on a 10-minute chart. Parameters:
- **%K period**: 14
- **Slowing**: 3
- **%D period**: 3
- **Overbought level**: 80 (alert set: %K is over 80)
- **Oversold level**: 20 (alert set: %K is under 20)

This is consistent with the RSI strategy — both indicators measure overbought/oversold conditions. Brian sets IG alerts at these levels and expects signals approximately once a week when multi-timeframe alignment occurs. He uses the overbought (80) alert as a potential sell/exit signal and oversold (20) as a buy signal.

### 6.2 Notification — IG vs This App

IG's built-in push handles simple single-threshold alerts. IG cannot fire on multi-timeframe alignment — this app's key differentiator. The two are complementary: IG contextualises *why* a signal fired; this app confirms *when* conditions are genuinely aligned.

**IG alert limitation**: IG alerts fire once then expire — Brian must manually rebuild each alert after it triggers. This app monitors continuously with no manual reset required.

---

## 7. Non-Functional Requirements

- Polling latency: RSI check every 10–30 seconds per instrument (configurable)
- Notification latency: alert within 5 seconds of signal detection
- Availability: 99%+ uptime — Railway/Render handle this automatically
- Security: API keys stored as environment variables, never hardcoded
- Private: no public endpoints, no user registration, two users only
- Auditability: all signals and actions logged to PostgreSQL with timestamps
- Resilience: graceful handling of IG API rate limits and downtime

---

## 8. Build Phases — Status

See `ROADMAP.md` for current phase status, prerequisites, costs, and next actions.

---

## 9. Collaboration & Ownership

- Private GitHub repo — keep private; do not open-source while geopolitical detection module is in development
- Both users receive all notifications via shared ntfy.sh topic
- Strategy parameters configurable via REST API or YAML config
- No commercialisation without mutual agreement and proper legal/regulatory advice
- **MiFID II**: tool is for personal use only — distributing signals commercially requires CBI/FCA authorisation
- **Brian's arrangement (March 2026)**: Brian offered to invest Ivan's savings and manage them for a percentage cut — consistent with how he manages funds for others. He wants demo trading validated first before committing live capital. He has asked Ivan to research gaps and test the app. No formal agreement in place yet — get terms in writing before proceeding.

---

## 10. Initial Scaffold

Project generated from a Windsurf prompt in a single session (March 2026). See git history for original scaffold prompt.

---

## 11. Geopolitical Risk & Anomalous Volume Detection

### 11.1 Context — The "TACO Trade" Pattern

A recurring pattern of suspicious pre-announcement trading around geopolitical decisions — dubbed the **"TACO trade"** — creates large, predictable market moves:

- **Iran, March 2026**: $580m in oil futures in one minute (~6,200 contracts vs ~700 average), 15 min before a strike pause announcement. S&P futures spiked simultaneously.
- **Liberation Day tariffs, April 2025**: bullish stock trades appeared minutes before a 90-day tariff pause was announced.
- **Venezuela, January 2026**: Polymarket trader turned $32k into $436k betting on Maduro's capture hours before the operation. A separate trader hit 22/23 correct predictions on US/Israeli military actions.

The app cannot predict this — but it can detect the fingerprint (anomalous volume) before the announcement lands.

### 11.2 The Detection Problem

The manipulation methods differ significantly across events — a single detector is insufficient:

| Event | Method | Amount | Detectable how? |
|---|---|---|---|
| Iran oil, March 2026 | Futures volume spike | $580m in 60 seconds (~6,200 contracts vs normal ~700) | Volume in transparent futures market — YES |
| Liberation Day tariffs, April 2025 | S&P futures spike pre-announcement | Large but unknown | Pre-market volume spike — YES |
| Venezuela/Maduro, Jan 2026 | Prediction market odds shift | Only $32k but moved illiquid market odds from 7% to 15% | Polymarket API odds shift — YES, different method |
| Google insider, 2025 | Prediction market, narrow focus | $1.2m across 22/23 correct bets | Pattern recognition over time — HARDER |

Two separate detectors are therefore needed — one for regulated futures/index volume, one for prediction market odds.

### 11.3 Volume Spike Detector (Regulated Markets) — ✅ BUILT

Do not scrape Truth Social or any social media. Detect the market fingerprint directly:

- Maintain a **rolling volume baseline per instrument per time-of-day** (critical — pre-market has naturally lower volume so baseline must be time-adjusted, not flat)
- Calculate rolling standard deviation of volume over a configurable lookback window (default: 20 trading days)
- **Dollar amount threshold**: flag when estimated notional value traded in a single minute exceeds a configurable amount. Suggested defaults: $200m for oil/indices (Iran was $580m — $200m catches it with margin), $50m for smaller instruments
- **Standard deviation threshold**: additionally flag when volume exceeds 4+ standard deviations above baseline regardless of dollar amount — catches smaller but still anomalous moves
- **Cross-instrument correlation alert**: oil volume spike AND S&P futures spike simultaneously in opposite directions is the strongest signal — the Iran pattern exactly
- Flag pre-market hours (approx 6:00–9:30am EST) separately — anomalies in this window are historically highest risk

### 11.4 Prediction Market Odds Detector (Polymarket/Kalshi) — ⏳ NOT YET BUILT

A separate lighter module monitoring geopolitical prediction market odds via public APIs:

- **Polymarket** has a public REST API — no authentication required for reading odds
- **Kalshi** also has a public API
- Monitor a configurable list of geopolitical contracts (e.g. "US strikes Iran", "North Korea missile test", "Russia ceasefire") — these are the contracts insiders would target
- **Alert trigger**: odds on any monitored contract shift by more than 8-10 percentage points within a 30-minute window on previously low volume — this is the Venezuela fingerprint ($32k moved odds 8 points on an illiquid market)
- **Cross-reference**: if a prediction market odds spike coincides with a futures volume spike — highest priority alert, pause all automated trading immediately
- Monitoring Polymarket is entirely legal — it is a public API with no terms violation

### 11.5 App Behaviour on Anomaly Detection — ✅ BUILT (volume spike path)

When either detector fires:

- Send an **urgent "ANOMALY DETECTED" alert** — highest ntfy.sh priority, visually distinct from RSI signals
- **Pause any pending automated trades** until picture clears
- Suggest correlated moves based on the pattern:

| Anomaly pattern | Approximate scale | Suggested consideration |
|---|---|---|
| Oil futures volume spike, price falling | >$200m/min or 4σ | S&P long likely incoming — Iran/OPEC deal pattern |
| Oil futures volume spike, price rising | >$200m/min or 4σ | Inflation/supply fear — Gold long, S&P caution |
| Pre-market S&P futures spike | >$100m/min before 9:30am EST | Major policy announcement imminent — hold all positions |
| Crypto volume spike downward | >$50m/min or 4σ | Risk-off — Gold long, wait before re-entering crypto |
| VIX sudden spike above 25 | Any scale | Reduce or close all longs, wait for clarity |
| Prediction market geopolitical odds shift >8% in 30 mins | Any dollar amount | Geopolitical event likely — do not open new positions |
| Both detectors firing simultaneously | Any scale | Highest alert — consider closing all open positions immediately |

### 11.6 High Uncertainty Mode — ⏳ NOT YET BUILT

Add a manual toggle — **"High Uncertainty Mode"** — that Ivan or Brian can activate:

- When active: RSI signals generate notifications only, no auto-execution under any circumstances
- Tighter stop recommendations flagged in alerts
- Volume spike threshold lowered from 4σ to 3σ
- Activate automatically if VIX exceeds a configurable threshold (default: 25)

### 11.7 Design Philosophy

This app cannot out-trade entities with advance knowledge of government decisions. The volume spike detector is not an attempt to replicate insider information — it is a defensive tool to avoid being on the wrong side of a manipulated move. The goal is damage limitation and situational awareness, not profit generation from geopolitical events.

RSI signals remain the primary trading logic. This module runs in parallel as a circuit breaker only.

---

*Private & Confidential — Not for distribution*
