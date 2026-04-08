# Troubleshooting — RSI Alert Service

## Quick Checks for New Agents

**Before changing any code**, verify IG API status with curl:

```bash
# 1. Check if IG auth works
CST=$(curl -s -X POST https://api.ig.com/gateway/deal/session \
  -H "X-IG-API-KEY: $IG_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"identifier":"'$IG_USERNAME'","password":"'$IG_PASSWORD'"}' \
  -D - | grep -i cst | head -1)

# 2. Check if DAX prices work (uses 1 data point)
curl -s -X GET "https://api.ig.com/gateway/deal/prices/IX.D.DAX.DAILY.IP/HOUR/1" \
  -H "X-IG-API-KEY: $IG_API_KEY" \
  -H "CST: $CST" \
  -H "X-SECURITY-TOKEN: $TOKEN"
```

## IG API Limits (Hard Caps)

| Limit | Value | Reset |
|-------|-------|-------|
| Historical data points | **10,000/week** | Weekly (likely Sunday/Monday UTC) |
| Non-trading requests/min | 60 | Rolling |
| Per-account requests/min | 30 | Rolling |

**Current config budget**: ~4,700 data points/week (6 IG instruments × 3 TFs, 15 min polling + candle skip)

## Common Error Codes

| Error Code | Meaning | Action |
|------------|---------|--------|
| `exceeded-account-historical-data-allowance` | 10k/week exceeded | **Stop all IG calls immediately**. Wait for weekly reset. Circuit breaker auto-opens. |
| `exceeded-api-key-allowance` | Per-minute limit hit | Circuit breaker opens for 1 hour. Reduce polling frequency. |
| `error.security.oauth-token-invalid` | Session expired | Re-auth automatically. |

## Circuit Breaker States

- **CLOSED**: Normal operation
- **OPEN**: IG allowance exceeded — all IG calls blocked for 1 hour (auto-resets)

Check logs: `IG circuit breaker OPEN` or `IG circuit breaker CLOSED`

## What to Change (and What NOT to)

**Safe changes** (no API impact):
- Signal thresholds, cooldown minutes, notification settings
- Add/remove Binance instruments
- Partial monitoring config

**Dangerous changes** (affect data point budget):
- `ig-interval-seconds` in `application.yml` — lower = more data points
- Adding IG instruments — each adds ~800 data points/week
- Enabling Gold/Oil — verify epic codes first with IG companion app

**Never do**:
- Poll IG faster than 15 min without checking budget
- Enable instruments with unknown/broken epic codes (they burn data points on every 403)

## Data Point Budget Formula

```
Weekly data points = instruments × timeframes × (market_hours / interval_seconds) × 7

Example: 6 IG instruments × 3 TFs × (16h / 15min) × 7 = ~4,700/week
```

## Files to Check

- `application.yml`: `ig-interval-seconds`, instrument list
- `docs/PROJECT_LOG.md`: Incident history
- `docs/RISK_REGISTER.md`: Known risks and mitigations
