# LucidLynx Market Signals — Architecture

*Last updated: April 2026*

---

## System Overview

Spring Boot service that polls market data APIs on a scheduled interval, calculates RSI, Stochastic, and volume anomalies across multiple timeframes per instrument, detects alignment signals, monitors Polymarket for geopolitical events, and pushes notifications. Runs in Docker with PostgreSQL for persistence.

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Boot App                          │
│                                                             │
│  ┌──────────────┐    ┌────────────────┐                     │
│  │  Scheduled   │───▶│ RSI Calculator │                     │
│  │  Poller      │    │  (per TF)      │                     │
│  └──────────────┘    └───────┬────────┘                     │
│                              │                              │
│                       ┌──────▼────────┐                     │
│                       │Signal Detector│                     │
│                       └──────┬────────┘                     │
│                              │ ApplicationEvent             │
│          ┌───────────────────┼──────────────────┐           │
│          ▼                   ▼                  ▼           │
│  ┌───────────────┐  ┌─────────────┐   ┌──────────────┐     │
│  │ Notification  │  │ IG Trading  │   │ Signal       │     │
│  │ Service       │  │ Service     │   │ Logger       │     │
│  │  (Telegram)    │  │ (Phase 4)   │   │ (Postgres)   │     │
│  └───────┬───────┘  └─────────────┘   └──────────────┘     │
│          │                                                  │
│  ┌───────▼───────┐  ┌─────────────────────────────┐        │
│  │ Claude AI     │  │ Anomaly Detection Layer     │        │
│  │ Enrichment    │  │ ┌─────────────────────────┐ │        │
│  │ (Phase 3)     │  │ │ VolumeAnomalyDetector   │ │        │
│  └───────────────┘  │ │ PolymarketMonitor       │ │        │
│                     │ └─────────────────────────┘ │        │
│                     └─────────────────────────────┘        │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              REST API (CRUD / Signals / Trading)     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
         │                          │
    ┌────▼──────┐            ┌──────▼───────┐
    │ IG REST   │            │ Binance API  │
    │ API       │            │ (free)       │
    └───────────┘            └──────────────┘
```

---

## Data Sources

| Source | Cost | Coverage | Status |
|--------|------|----------|--------|
| Binance | FREE | Crypto (SOL, BTC, ETH, BCH) | Live |
| IG API | FREE (with account) | Indices, FX, commodities, crypto | Live |

---

## Component Responsibilities

### Core Pipeline

- **`MarketDataService`** — polls Binance and IG APIs every 60s, fetches OHLC candles per instrument per timeframe
- **`RSICalculatorService`** — Wilder's smoothing RSI (period=14) from close prices; maintains in-memory candle history
- **`SignalDetectorService`** — evaluates RSI alignment across configured timeframes; emits FULL, PARTIAL, or WATCH signals
- **`NotificationService`** — formats and sends alerts via Telegram; handles quiet hours, cooldown, no-trade mode, weekend suppression, per-signal-type priority levels

### Signal Monitoring

- **`PartialSignalMonitorService`** — tracks lagging timeframe after PARTIAL fires; sends follow-ups (only when gap is closing); fires FULL ALIGNMENT or expiry notification. Window: 60 min, interval: 30 min.
- **`AlertCsvService`** — appends every signal to `signal_alerts_YYYY-MM.csv` with full OHLCV candle snapshot; hourly job backfills outcome prices (1h/4h/24h later)
- **`HistoryArchivalService`** — weekly job exports signal_logs older than 90 days to CSV

### Anomaly Detection (Phase 5)

- **`VolumeAnomalyDetector`** — rolling baseline per instrument; fires on configurable σ threshold (default 5.5σ)
- **`PolymarketMonitorService`** — polls configured prediction market slugs every 5 min; fires on odds shifts ≥8pp
- **`AnomalyNotificationService`** — urgent alerts that bypass quiet hours

### Trading (Phase 4 — hard-disabled)

- **`IGTradingService`** — kill switch, daily loss limit, max concurrent positions, manual approval by default
- **`IGAuthService`** — IG API authentication with session auto-refresh every 6 hours

### AI Enrichment (Phase 3 — disabled by default)

- **`ClaudeEnrichmentService`** — optional Claude API call to add news context to signal notifications

---

## Instrument Configuration

Instruments are seeded from `application.yml` on startup via `DataInitializer` and stored in PostgreSQL. Each instrument has:
- **Symbol** (e.g. `SOLUSDT`, `IX.D.DAX.DAILY.IP`)
- **Source** (`BINANCE` or `IG`)
- **Timeframes** — configurable per instrument
- **Thresholds** — oversold/overbought, configurable per instrument

| Instrument type | Timeframes | Rationale |
|---|---|---|
| Indices (DAX, FTSE, S&P, Nasdaq) | 15m, 30m, 1h | Fast V-recoveries; need responsiveness |
| Crypto (BTC, ETH, SOL, BCH) | 15m, 1h, 4h | Larger moves; 4h confirmation valuable |
| Commodities (Gold, Silver, Oil) | 15m, 1h, 4h | Slower trends; 4h confirmation valuable |

---

## Signal Hierarchy

| Priority | Signal | Condition | Priority |
|---|---|---|---|
| 1 | OVERSOLD / OVERBOUGHT | All TFs aligned | urgent (5) — bypasses DND |
| 2 | PARTIAL | All but 1 TF aligned | default (3) |
| 3 | WATCH | 1 TF crossed + others approaching (<40 or >60) | low (2) |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.5.3 |
| Language | Java 25 |
| Database | PostgreSQL 16 |
| HTTP Client | Spring WebFlux (reactive) |
| Notifications | Telegram Bot API |
| AI | Claude API (Anthropic) — optional |
| Build | Maven |
| Container | Docker + docker-compose |
| Hosting | Railway.app (~€5/month) or local Docker |

---

*See `README.md` for setup. See `docs/api.md` for endpoint reference.*
