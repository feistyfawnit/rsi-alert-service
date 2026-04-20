# Remote Deployment Guide

*AWS, Oracle Cloud, and Alibaba Cloud options*

---

## AWS ✅ ACTIVE — eu-west-1 (Dublin)

**Status:** Live instance running — `108.128.230.238`

**Quick launch (for reference/future):**
- Console → EC2 → Launch Instance → Ubuntu 24.04 → t3.micro (free tier)
- **Region: eu-west-1 (Ireland)** — ~5ms latency from Dublin
- Security Group: Port 22 (SSH) + Port 8080 (app) from **My IP only**
- Elastic IP: Allocate and associate for static IP
- Storage: 30GB gp3
- SSH key: `market-signals.pem` in `~/.ssh/` (chmod 600)

**Post-launch setup:**
```bash
# 1. SSH and create directory
ssh -i ~/.ssh/market-signals.pem ubuntu@YOUR_ELASTIC_IP
mkdir -p ~/apps/rsi-alert-service && cd ~/apps/rsi-alert-service

# 2. From Mac — copy files (code + .env + backup)
scp -i ~/.ssh/market-signals.pem -r docker-compose.yml Dockerfile pom.xml src .env backup-*.sql \
  ubuntu@YOUR_ELASTIC_IP:/home/ubuntu/apps/rsi-alert-service/

# 3. On EC2 — restore DB and start
ssh -i ~/.ssh/market-signals.pem ubuntu@YOUR_ELASTIC_IP
cd ~/apps/rsi-alert-service
docker-compose up -d postgres
sleep 5
docker exec -i market-signals-postgres psql -U postgres -c "CREATE DATABASE market_signals;"
docker exec -i market-signals-postgres psql -U postgres market_signals < backup-*.sql
docker-compose down && docker-compose up -d --build

# 4. Enable IG instruments (IDs may vary — check /api/instruments first)
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/5/toggle  # DAX
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/6/toggle  # FTSE
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/7/toggle  # S&P 500
curl -X PATCH http://YOUR_ELASTIC_IP:8080/api/instruments/8/toggle  # Gold
```

**Cost:** Free 12 months, then ~€15/month.

---

## Oracle Cloud (Truly Free Forever)

**€0 forever** — 4 OCPUs + 24GB RAM (ARM) or 2 VMs (AMD). Signup is flaky but worth it for zero ongoing cost.

### 1. Sign Up
- https://cloud.oracle.com/free
- Pick region: **eu-frankfurt-1** (closest to Ireland)
- Credit card for verification (not charged)
- If stuck at "That name didn't work" — wait 10 mins, refresh, try incognito

### 2. Create VM
- Compute → Instances → Create
- Image: **Ubuntu 22.04**
- Shape: **VM.Standard.A1.Flex** (ARM, always free)
- OCPU: 1, Memory: 6GB
- Boot volume: 50GB
- Download SSH key

### 3. Open Ports
- Networking → VCN → Subnet → Default Security List → Add Ingress:
  - Port 22 (SSH) from your IP
  - Port 8080 (app) from your IP

### 4. Connect & Setup
```bash
chmod 600 ~/Downloads/ssh-key-*.key
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP

# Install Docker
sudo apt update && sudo apt install -y docker.io docker-compose
sudo usermod -aG docker $USER && newgrp docker

# Create directory
mkdir -p ~/apps/rsi-alert-service && cd ~/apps/rsi-alert-service
```

### 5. Deploy
```bash
# From Mac — copy files
scp -i ~/Downloads/ssh-key-*.key \
  docker-compose.yml Dockerfile pom.xml src .env \
  ubuntu@ORACLE_IP:/home/ubuntu/apps/rsi-alert-service/

# On Oracle VM — start
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP
cd ~/apps/rsi-alert-service
docker-compose up -d --build
```

### 6. DB Migration (if moving from MacBook)
```bash
# On MacBook first
make up
sleep 10
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup.sql
make down

# Copy to Oracle
scp -i ~/Downloads/ssh-key-*.key backup.sql ubuntu@ORACLE_IP:/home/ubuntu/apps/rsi-alert-service/

# On Oracle VM — restore
ssh -i ~/Downloads/ssh-key-*.key ubuntu@ORACLE_IP
cd ~/apps/rsi-alert-service
docker-compose up -d postgres
sleep 5
docker exec -i market-signals-postgres psql -U postgres -c "CREATE DATABASE market_signals;"
docker exec -i market-signals-postgres psql -U postgres market_signals < backup.sql
docker-compose down && docker-compose up -d --build
```

**Cost: €0 forever.**

---

## Alibaba Cloud (Simpler Alternative)

€0 free tier (1 year), then ~€4-5/month. Easier signup than Oracle.

### 1. Sign Up
- https://www.alibabacloud.com
- Email + phone verification
- Add payment method

### 2. Create Instance
- Console → Elastic Compute Service → Create Instance
- Region: **Frankfurt (eu-central-1)**
- Instance: **ecs.t6-c1m1.large** (free tier eligible)
- Image: Ubuntu 22.04
- Storage: 40GB

### 3. Security Group
- Add rules: Port 22 (SSH), Port 8080 (app) from your IP

### 4. Deploy
Same steps as Oracle — SSH in, install Docker, copy files, start services.

**Cost:** Free 1 year, then ~€4/month.

---

## CI/CD Pipeline

Works with any provider — update `secrets.EC2_HOST` and `secrets.EC2_SSH_KEY` in GitHub.

See `.github/workflows/deploy.yml` in repo root.

---

## Database Backup/Restore

**Backup (any running instance):**
```bash
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup-$(date +%F).sql
```

**Restore:**
```bash
docker exec -i market-signals-postgres psql -U postgres market_signals < backup.sql
```

---

## Current Status

| Provider | Status | Cost |
|----------|--------|------|
| **AWS eu-west-1** | ✅ **ACTIVE** — Instance running, 5 instruments enabled | Free (12 months), then ~€15/mo |
| AWS us-west-2 | ❌ Terminated | — |
| Oracle | ⏸️ Not needed — AWS working | €0 (if wanted later) |
| Alibaba | ⏸️ Not needed — AWS working | ~€4/mo post-free-tier |

---

## Historical Data & P&L Analysis

**Already built in:**

| Feature | Endpoint | Description |
|---------|----------|-------------|
| **P&L Report** | `GET /api/positions/pnl-summary` | Live JSON with open/closed positions, win rate, expectancy |
| **Daily Report** | `./reports/pnl-report.md` | Auto-generated markdown at 06:00 UTC |
| **Signal History** | `signal_logs` table | Every signal stored with timestamp, price, RSI values |
| **Candle History** | `candle_history` table | 2,885+ candles persisted, RSI calculated from full history |
| **Backtest Analysis** | See `docs/backtest-report.md` | Manual analysis of signal quality (TP/SL hit rates) |

**Generate report now:**
```bash
curl http://108.128.230.238:8080/api/positions/pnl-summary
```

**View on disk:**
```bash
ssh -i ~/.ssh/market-signals.pem ubuntu@108.128.230.238
cat ~/apps/rsi-alert-service/reports/pnl-report.md
```

---

*Last updated: April 2026*
