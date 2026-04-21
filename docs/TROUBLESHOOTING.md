# Troubleshooting — LucidLynx Market Signals

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

**Current config budget**: ~5,300 data points/week (7 IG instruments, 15 min polling + candle-period skip)

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
- Adding IG instruments — each adds roughly 600–800 data points/week depending on timeframes
- Changing IG epic codes without verifying them via IG search + one-candle curl test

**Never do**:
- Poll IG faster than 15 min without checking budget
- Enable instruments with unknown/broken epic codes (they burn data points on every 403)

## Data Point Budget Formula

```
Weekly data points = instruments × timeframes × (market_hours / interval_seconds) × 7

Current rough mix:
- 4 indices × (15m,30m,1h)
- 3 commodities × (15m,1h,4h)
- Total ≈ ~5,300/week with candle-period skip
```

## Single Instance Rule — Never Run Local + AWS Simultaneously

**AWS EC2 is the primary instance.** Running both local and AWS at the same time doubles IG API data point consumption (~900+ extra points/day) and can exhaust the 10,000/week allowance mid-week.

`make up` is guarded — it will refuse to start and print a warning unless you pass `LOCAL_RUN=yes`:

```bash
make up LOCAL_RUN=yes   # deliberate local run (e.g. dev/testing)
make ship               # normal path — deploy to AWS and watch logs
```

**If you accidentally started local:** run `make down` immediately, then check IG budget by reviewing logs for the number of warmup/poll calls made.

**SSH to EC2 timed out?** Your home IP may have changed. Update the security group inbound rule:
- Check your current IP: `curl https://checkip.amazonaws.com`
- AWS Console → EC2 → Security Groups → **`launch-wizard-1` (sg-0a6f709becceb1930)** → Edit inbound rules
- Update the SSH source to your new IP (or `0.0.0.0/0` if GitHub Actions auto-deploy is active)
- Update the 8080 source to your new IP

**There are two security groups on this instance** — only `launch-wizard-1` (sg-0a6f709becceb1930) has the inbound rules. The other (`sg-058ef1c68f3d27ea5`) is the default VPC group — ignore it.

**Current inbound rules on `launch-wizard-1`:**

| Port | Protocol | Source | Purpose |
|------|----------|--------|---------|
| 22 (SSH) | TCP | `0.0.0.0/0` | SSH + GitHub Actions deploy |
| 8080 | TCP | your IP `/32` | App API — keep restricted |

---

## SSL / PKIX Certificate Error (Polymarket)

**Symptom:**
```
SSLHandshakeException: PKIX path building failed: unable to find valid certification path to requested target
```
Target: `gamma-api.polymarket.com`

**Cause:** Alpine-based Docker images sometimes ship with an incomplete or stale CA certificate bundle. The JVM cannot verify the TLS certificate chain.

**Fix:** `Dockerfile` runtime stage now runs `apk add --no-cache ca-certificates && update-ca-certificates`. Rebuild to apply:
```bash
make up        # local
make deploy    # AWS EC2
```

**Not caused by:** Polymarket API being down, networking issues, or app config.

---

## `make` Targets — Local vs AWS

`make` runs on your **Mac only**. The EC2 Ubuntu instance does not have `make` installed.

| Command | Where it runs | What it does |
|---------|--------------|--------------|
| `make up` | Local (Colima) | Start local Docker stack |
| `make logs` | Local (Colima) | Tail local container logs |
| `make deploy` | Mac → SSH → EC2 | Pull, rebuild, restart on AWS |
| `make remote-logs` | Mac → SSH → EC2 | Tail live EC2 logs |
| `make ship` | Mac → SSH → EC2 | Deploy then tail (one command) |

**Note:** `make logs` and `make remote-logs` are independent — local Docker can run alongside AWS at the same time.

---

## Files to Check

- `application.yml`: `ig-interval-seconds`, instrument list
- `docs/project-log.md`: Incident history
- `docs/risk-register.md`: Known risks and mitigations
