# Deploy to Alibaba Cloud (Aliyun) Free Tier

*Simpler than AWS/Oracle, Frankfurt EU region, 12 months free*

---

## Why Alibaba Cloud for this project

- **Simpler console** than AWS or Oracle — fewer nested menus, clearer navigation
- **Frankfurt data center** — ~20ms from Ireland, GDPR-compliant EU region
- **12 months free** ECS instance (1 vCPU, 1GB RAM, 40GB SSD) — same duration as AWS
- **Cheaper after free tier** — ~€3-4/month vs AWS ~€15-20/month for similar specs
- **Chinese company** — you mentioned trusting them more (noted: consider your threat model for financial data)

**Trade-off:** Low CV value in Ireland (AWS dominates). But for a personal project? Doesn't matter.

---

## Prerequisites

- Alibaba Cloud account (international site: https://www.alibabacloud.com)
- Credit card (verification only, not charged during free tier)
- SSH key pair
- Telegram bot token (already have locally)
- Optional: IG API credentials

---

## Step 1 — Sign Up

1. Go to https://www.alibabacloud.com
2. Click **Free Account** → enter email, password
3. Verify email
4. Add payment method (credit card for identity verification)
5. Complete real-name verification (required for free tier)

---

## Step 2 — Create ECS Instance (VM)

1. Console → **Products** → **Elastic Compute Service (ECS)**
2. Click **Create Instance**
3. Configure:

| Setting | Value |
|---------|-------|
| Billing Method | **Pay-As-You-Go** (enables free tier) |
| Region | **Germany (Frankfurt)** — `eu-central-1` |
| Instance Type | **ecs.t5-lc1m1.small** (1 vCPU, 1GB RAM) — or any with "Free Tier Eligible" badge |
| Image | **Ubuntu 22.04 64-bit** |
| System Disk | **ESSD Entry 40GB** (free tier eligible) |
| Network Type | VPC (default is fine) |
| Public IP | **Assign Public IPv4 Address** — Yes |
| Security Group | Create new or use default |
| Logon Credentials | **Key Pair** — create or upload SSH public key |
| Instance Name | `market-signals` |

4. Click **Preview** → **Create Instance**
5. Note the **Public IP** assigned (e.g., `47.254.123.45`)

---

## Step 3 — Configure Security Group (Firewall)

Alibaba Cloud security groups are **deny-all by default** — you must explicitly open ports.

1. Console → **ECS** → **Security Groups**
2. Find the security group attached to your instance
3. Click **Configure Rules** → **Inbound Rules** → **Add Rule**

**Rule 1 — SSH:**
- Type: **SSH (22)**
- Source: **0.0.0.0/0** (or your IP/32 for tighter security)

**Rule 2 — Application:**
- Protocol: **Custom TCP**
- Port Range: **8080**
- Source: **0.0.0.0/0** (or your IP/32)

**Rule 3 — HTTPS (optional, if using reverse proxy):**
- Type: **HTTPS (443)**
- Source: 0.0.0.0/0

> 🔒 **Security tip:** Restrict 8080 to your home IP only. In Ireland, most ISPs give semi-static IPs — check with `curl ifconfig.me` and use that IP/32.

---

## Step 4 — Connect to Server

```bash
# SSH using your private key
ssh -i ~/.ssh/alibaba_key.pem ubuntu@YOUR.PUBLIC.IP

# Or if you used a different key name
ssh -i ~/.ssh/id_rsa ubuntu@47.254.123.45
```

---

## Step 5 — Install Docker

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker (official script)
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add user to docker group
sudo usermod -aG docker $USER
newgrp docker

# Install Docker Compose plugin
sudo apt install docker-compose-plugin -y

# Verify
docker --version
docker compose version
```

---

## Step 6 — Deploy Application

```bash
# Create app directory
mkdir -p ~/apps && cd ~/apps

# Clone your repo
git clone https://github.com/yourusername/rsi-alert-service.git
# OR: git clone git@github.com:yourusername/rsi-alert-service.git

cd rsi-alert-service
```

### Transfer Environment File

**From your Mac:**
```bash
scp -i ~/.ssh/alibaba_key.pem /Users/terryi/Windsurf/rsi-alert-service/.env ubuntu@YOUR.IP:/home/ubuntu/apps/rsi-alert-service/
```

**Or create on server:**
```bash
cat > .env << 'EOF'
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_token_here
TELEGRAM_CHAT_IDS=your_chat_id_here
IG_ENABLED=false
SERVER_ADDRESS=0.0.0.0
EOF
```

> ⚠️ **Critical:** Must set `SERVER_ADDRESS=0.0.0.0` so Spring Boot accepts connections from outside localhost.

### Start Service

```bash
docker compose up -d --build

# Watch logs
docker compose logs -f app
```

### Test

```bash
# From your Mac
curl http://YOUR.IP:8080/actuator/health
curl http://YOUR.IP:8080/api/instruments/enabled
make pnl-report  # Will work if you update Makefile or use curl directly
```

---

## Step 7 — (Optional) Free SSL with Nginx + Let's Encrypt

If you want HTTPS without Cloudflare:

```bash
# Install nginx and certbot
sudo apt install nginx certbot python3-certbot-nginx -y

# Configure nginx reverse proxy
sudo nano /etc/nginx/sites-available/market-signals
```

```nginx
server {
    listen 80;
    server_name signals.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }
}
```

```bash
# Enable site
sudo ln -s /etc/nginx/sites-available/market-signals /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl restart nginx

# Get SSL certificate
sudo certbot --nginx -d signals.yourdomain.com

# Auto-renewal is configured automatically
```

---

## Step 8 — Monitoring & Maintenance

### Check Resources

With 1GB RAM, you need to watch memory:

```bash
# Memory usage
free -h

# Disk usage
df -h

# Running containers
docker ps

# Container stats
docker stats
```

### Add Swap (Recommended for 1GB RAM)

```bash
# Create 2GB swap
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# Make permanent
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### Backup Database

```bash
# Create backup
docker exec market-signals-postgres pg_dump -U postgres market_signals > ~/backup_$(date +%F).sql

# Download to Mac
scp ubuntu@YOUR.IP:~/backup_*.sql /Users/terryi/Downloads/
```

### Update Application

```bash
cd ~/apps/rsi-alert-service
git pull
docker compose down
docker compose up -d --build
```

---

## Cost After Free Tier

| Component | Monthly Cost |
|-----------|-------------|
| ecs.t5-lc1m1.small (1 vCPU, 1GB) | ~€2.50 |
| 40GB ESSD storage | ~€0.50 |
| 100GB data transfer | ~€1.00 |
| **Total** | **~€4/month** |

Compare to AWS t2.micro: ~€15-20/month after free tier.

---

## Troubleshooting

### Can't connect to port 8080
```bash
# Check security group has port 8080 open
# Check .env has SERVER_ADDRESS=0.0.0.0
# Check: curl http://localhost:8080/actuator/health (from server)
```

### Out of memory kills
```bash
# Check if swap is active
swapon -s

# Check OOM kills in dmesg
dmesg | grep -i kill
```

### Container keeps restarting
```bash
# Check logs
docker compose logs app

# Likely causes:
# - Missing .env file
# - Wrong TELEGRAM_BOT_TOKEN
# - Port 8080 already in use
```

---

## Security Checklist

- [ ] Security Group: Port 22 restricted to your IP
- [ ] Security Group: Port 8080 restricted or behind HTTPS
- [ ] .env file has 0600 permissions
- [ ] No secrets in git (check `git log --all --full-history -- .env`)
- [ ] IG demo account credentials only (if enabled)
- [ ] TRADING_AUTO_EXECUTION_ENABLED stays false
- [ ] Regular updates: `sudo apt update && sudo apt upgrade -y`

---

## Migration from Local to Alibaba Cloud

If you have local data you want to preserve:

```bash
# On your Mac — backup local database
docker exec rsi-alert-service-postgres-1 pg_dump -U postgres market_signals > local_backup.sql

# Transfer to Alibaba server
scp local_backup.sql ubuntu@YOUR.IP:~/

# On Alibaba server — restore
# (After first docker compose up creates the empty DB)
docker cp ~/local_backup.sql market-signals-postgres:/tmp/
docker exec -it market-signals-postgres psql -U postgres -c "DROP DATABASE market_signals;"
docker exec -it market-signals-postgres psql -U postgres -c "CREATE DATABASE market_signals;"
docker exec -it market-signals-postgres psql -U postgres market_signals -f /tmp/local_backup.sql
```

---

## Next Steps

1. **Verify it's running:** `curl http://YOUR.IP:8080/actuator/health`
2. **Wait for first signal:** Paper trade log continues, but now 24/7
3. **Monitor P&L:** `curl http://YOUR.IP:8080/api/positions/pnl-summary`
4. **Consider upgrading:** If 1GB RAM becomes tight (many instruments), upgrade to 2GB (~€8/month) or optimize JVM heap

---

## Comparison Summary

| | Alibaba Cloud | AWS | Oracle |
|---|---|---|---|
| **Setup time** | 30 min | 60 min | 2-3 hours |
| **Console UX** | Good | Complex | Very complex |
| **Free tier duration** | 12 months | 12 months | Forever |
| **After free tier** | ~€4/mo | ~€15/mo | €0 |
| **CV value (Ireland)** | Low | **High** | Low |
| **EU data center** | Frankfurt | Dublin ✅ | Frankfurt |

**Bottom line:** Alibaba is the pragmatic choice for getting this running quickly and cheaply. AWS is the pragmatic choice if you want to build AWS skills for the Irish job market.

---

*Last updated: April 2026*
