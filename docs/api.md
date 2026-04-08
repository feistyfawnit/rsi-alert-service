# API Reference

*Last updated: April 2026*

Base URL: `http://localhost:8080`

---

## Instruments — `/api/instruments`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/instruments` | List all instruments |
| `GET` | `/api/instruments/enabled` | List enabled instruments only |
| `GET` | `/api/instruments/{id}` | Get instrument by ID |
| `POST` | `/api/instruments` | Create instrument |
| `PUT` | `/api/instruments/{id}` | Update instrument |
| `PATCH` | `/api/instruments/{id}/toggle` | Toggle enabled/disabled |
| `DELETE` | `/api/instruments/{id}` | Delete instrument |

### Create/Update payload

```json
{
  "symbol": "ETHUSDT",
  "name": "Ethereum",
  "source": "BINANCE",
  "type": "CRYPTO",
  "enabled": true,
  "oversoldThreshold": 30,
  "overboughtThreshold": 70,
  "timeframes": "15m,1h,4h"
}
```

---

## Signals — `/api/signals`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/signals` | All signal logs |
| `GET` | `/api/signals/symbol/{symbol}` | Signals for a specific instrument |
| `GET` | `/api/signals/recent?hours=24` | Signals from last N hours |
| `GET` | `/api/signals/rsi-snapshot` | Live RSI values for all enabled instruments (from in-memory history) |
| `GET` | `/api/signals/retrospective/{symbol}?at=ISO8601` | Historical RSI analysis — would a signal have fired at this time? (IG instruments only) |

### Retrospective example

```
GET /api/signals/retrospective/IX.D.DAX.DAILY.IP?at=2026-04-02T12:30:00Z
```

Returns per-timeframe RSI values, distance from thresholds, and verdict (FULL / PARTIAL / No signal).

---

## Per-Instrument Muting — `/api/signals/mute`

Suppresses **all** notifications (FULL, PARTIAL, WATCH) for a specific instrument. Use when you can't trade a particular instrument (e.g. crypto unavailable, IG instrument suspended). In-memory only — resets on restart.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/signals/mute/{symbol}` | Mute all alerts for a symbol (e.g. `BTCUSDT`, `ETHUSDT`) |
| `POST` | `/api/signals/unmute/{symbol}` | Re-enable alerts for a symbol |
| `GET` | `/api/signals/muted` | List currently muted symbols |

---

## No-Trade Mode — `/api/signals/no-trade-mode`

Suppresses PARTIAL and WATCH notifications for **all** instruments. FULL signals still fire.
In-memory only — resets on restart.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/signals/no-trade-mode/on` | Enable no-trade mode |
| `POST` | `/api/signals/no-trade-mode/off` | Disable no-trade mode |
| `GET` | `/api/signals/no-trade-mode` | Current status |

---

## Trading — `/api/trading`

⚠️ Phase 4 — hard-disabled by default. Do not enable without demo validation.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/trading/status` | Auto-execution and kill switch status |
| `POST` | `/api/trading/kill-switch/activate` | Emergency stop — halts all auto-trading |
| `POST` | `/api/trading/kill-switch/deactivate` | Re-enable auto-trading |

---

## Test / Demo — `/api/test`

For development and demo purposes. See `QUICKSTART.md` for usage.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/test/notify` | Fire synthetic OVERSOLD signal (labelled `[TEST]`) |
| `POST` | `/api/test/anomaly?type=volume` | Fire test volume spike anomaly alert |
| `POST` | `/api/test/anomaly?type=polymarket` | Fire test Polymarket odds shift alert |
| `POST` | `/api/test/lower-thresholds?oversold=50&overbought=50` | Lower thresholds to trigger real signals on next poll |
| `POST` | `/api/test/reset-thresholds` | Reset all thresholds to 30/70 |
| `GET` | `/api/test/ig/search?term=DAX` | Search IG epic codes |

---

## Health

```
GET /actuator/health
```

---

*See `docs/architecture.md` for system design. See `README.md` for setup.*
