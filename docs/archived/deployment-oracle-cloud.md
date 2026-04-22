# Deploy to Oracle Cloud Free Tier

*Zero-cost, always-on hosting with full VM control*

---

## Overview

Oracle Cloud Free Tier provides **always-free** resources that fit this service perfectly:
- **ARM Ampere A1**: 4 OCPUs + 24GB RAM (flexible allocation — e.g., 1 VM with 4 cores, or 4 VMs with 1 core each)
- **AMD**: 2 VMs with 1/8 OCPU + 1GB RAM each
- **Storage**: 200GB block volume free
- **Network**: 10TB egress/month free
- **Price**: $0 forever (not a trial)

**Why this beats Railway/Render for your use case:**
- ✅ Free forever (Railway is $5/mo minimum for always-on)
- ✅ Your own VM — better isolation for financial data
- ✅ No platform lock-in — standard Ubuntu + Docker
- ✅ 24/7 uptime with zero cost

**Trade-off:** ~2–3 hours initial setup vs 10 minutes on Railway.

---

## Prerequisites

- Oracle Cloud account (free tier signup)
- SSH key pair (create fresh for this project)
- Domain name (optional — can use IP + Cloudflare Tunnel for free SSL)
- Telegram bot token (already configured locally)
- IG API credentials (if using indices)

---

## Step 1 — Sign Up for Oracle Cloud Free Tier

1. Go to https://cloud.oracle.com/free
2. Sign up with email (personal is fine)
3. Verify identity (credit card required for verification only — **not charged**)
4. Select home region: **eu-frankfurt-1** (lowest latency from Ireland) or **uk-london-1**

> ⚠️ **Important:** Once you pick a region, all resources stay there. Pick the closest to you.

---

## Step 2 — Create a VM (Compute Instance)

### Using the Console

1. Navigation menu → **Compute** → **Instances**
2. Click **Create Instance**
3. Configure:

| Setting | Value |
|---------|-------|
| Name | `market-signals` |
| Placement | Availability Domain: AD-1 (any) |
| Image | **Oracle Linux 9** or **Canonical Ubuntu 22.04** (recommended — more familiar) |
| Shape | **VM.Standard.A1.Flex** (ARM, always free) |
| OCPUs | 1 |
| Memory | 6GB (plenty for this service) |
| Boot volume | 50GB (default is fine) |
| Add SSH keys | **Generate new key pair** OR **Upload public key** (if you have one) |

4. Click **Create**
5. Download the private key if generated

### Wait for Provisioning

- Status will show "Provisioning" → "Running" (2–3 minutes)
- Note the **Public IP address** assigned

---

## Step 3 — Configure Security (Firewall)

Oracle Cloud has strict default security. You must explicitly open ports.

### 3a — Update VCN Security List (Subnet Level)

1. Navigation → **Networking** → **Virtual Cloud Networks**
2. Click your VCN (usually named like `vcn-2026-...`)
3. Click the subnet (e.g., `public-subnet-...`)
4. Click **Default Security List**
5. Click **Add Ingress Rules**:

**Rule 1 — SSH:**
- Source Type: CIDR
- Source CIDR: `0.0.0.0/0` (or your home IP/32 for tighter security)
- IP Protocol: TCP
- Destination Port Range: `22`

**Rule 2 — Application:**
- Source Type: CIDR
- Source CIDR: `0.0.0.0/0` (or your home IP/32 for admin-only)
- IP Protocol: TCP
- Destination Port Range: `8080`

> 🔒 **Security tip:** For production, restrict 8080 to your IP only. Use a reverse proxy (nginx/traefik) with basic auth if exposing more broadly.

### 3b — Enable Internet Access (If Needed)

The default setup should have internet access. Verify:
1. VCN → Internet Gateway — should exist
2. Route Table → should have route `0.0.0.0/0` pointing to Internet Gateway

---

## Step 4 — Connect to Your VM

```bash
# If you downloaded Oracle's generated key
chmod 600 ~/Downloads/ssh-key-*.key

# Connect (Ubuntu image uses 'ubuntu' user; Oracle Linux uses 'opc')
ssh -i ~/Downloads/ssh-key-*.key ubuntu@YOUR.PUBLIC.IP.ADDRESS

# Or if you uploaded your own key
ssh -i ~/.ssh/id_rsa ubuntu@YOUR.PUBLIC.IP.ADDRESS
```

---

## Step 5 — Install Docker & Docker Compose

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add user to docker group (logout/login required after)
sudo usermod -aG docker $USER
newgrp docker

# Install Docker Compose (plugin version)
sudo apt install docker-compose-plugin -y

# Verify
docker --version
docker compose version
```

---

## Step 6 — Clone Repository & Configure

```bash
# Create app directory
mkdir -p ~/apps && cd ~/apps

# Clone (you'll need to set up SSH key with GitHub, or use HTTPS)
git clone git@github.com:yourusername/market-signals.git
# OR: git clone https://github.com/yourusername/market-signals.git

cd market-signals
```

### Create Environment File

```bash
# Copy example and edit
# (Transfer your local .env securely — see Security Notes below)
nano .env
```

**Minimum required for Binance-only (crypto):**
```bash
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_CHAT_IDS=your_chat_id_here
IG_ENABLED=false
CLAUDE_ENABLED=false
TRADING_AUTO_EXECUTION_ENABLED=false
```

**With IG indices (optional):**
```bash
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_CHAT_IDS=your_chat_id_here
IG_ENABLED=true
IG_API_KEY=your_ig_key
IG_USERNAME=your_ig_username
IG_PASSWORD=your_ig_password
IG_BASE_URL=https://demo-api.ig.com/gateway/deal
CLAUDE_ENABLED=false
TRADING_AUTO_EXECUTION_ENABLED=false
```

### Transfer .env Securely (from your Mac)

**Option A — SCP:**
```bash
# From your local machine
scp -i ~/.ssh/oracle_key ~/.windsurf/market-signals/.env ubuntu@YOUR.IP:/home/ubuntu/apps/market-signals/
```

**Option B — SSH copy-paste:**
```bash
# On the VM
cat > .env << 'EOF'
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
# ... rest of env
EOF
```

---

## Step 7 — Configure Application for Production

Edit `src/main/resources/application.yml` if needed, or use environment overrides.

**Critical: Change server port binding for remote access**

By default, Spring Boot binds to `localhost:8080` only. For remote access, you need:

```bash
# Option 1: Set env var
export SERVER_ADDRESS=0.0.0.0

# Option 2: Add to .env file (docker-compose will pass it)
echo "SERVER_ADDRESS=0.0.0.0" >> .env
```

Or modify `docker-compose.yml` to pass it:
```yaml
environment:
  SERVER_ADDRESS: 0.0.0.0
```

---

## Step 8 — Start the Service

```bash
# Build and start
docker compose up -d --build

# Watch logs
docker compose logs -f app

# Verify health
curl http://localhost:8080/actuator/health
```

**Test from your local machine:**
```bash
curl http://YOUR.IP.ADDRESS:8080/actuator/health
curl http://YOUR.IP.ADDRESS:8080/api/instruments/enabled
```

---

## Step 9 — Set Up Persistent Storage

The default `docker-compose.yml` uses a named volume for PostgreSQL. On Oracle Cloud, data persists across container restarts but **not** across VM recreation.

For extra safety, mount a block volume:

```bash
# Create mount point
sudo mkdir -p /data/postgres

# Attach and mount block volume (if you created one in OCI console)
# See: https://docs.oracle.com/en-us/iaas/Content/Block/Tasks/connectingtoavolume.htm

# Update docker-compose.yml to use host path
# volumes:
#   - /data/postgres:/var/lib/postgresql/data
```

---

## Step 10 — Process Management (Auto-Start on Boot)

Enable Docker auto-start:
```bash
sudo systemctl enable docker
```

The `docker-compose.yml` already has `restart: unless-stopped`, so containers auto-start on boot.

---

## Step 11 — (Optional) Free SSL with Cloudflare Tunnel

If you want HTTPS without buying a domain or certificate:

1. **Get a free domain:**
   - https://www.freenom.com (free .tk, .ml domains)
   - Or use a subdomain of a domain you own

2. **Cloudflare setup:**
   - Add domain to Cloudflare (free plan)
   - Change nameservers as instructed

3. **Install cloudflared on VM:**
```bash
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
sudo mv cloudflared-linux-amd64 /usr/local/bin/cloudflared
sudo chmod +x /usr/local/bin/cloudflared
cloudflared --version
```

4. **Authenticate:**
```bash
cloudflared tunnel login
# Follow URL, select domain, download cert
```

5. **Create tunnel:**
```bash
cloudflared tunnel create market-signals
# Note the tunnel UUID

cloudflared tunnel route dns market-signals signals.yourdomain.com

# Create config
cat > ~/.cloudflared/config.yml << 'EOF'
tunnel: YOUR_TUNNEL_UUID
credentials-file: /home/ubuntu/.cloudflared/YOUR_TUNNEL_UUID.json

ingress:
  - hostname: signals.yourdomain.com
    service: http://localhost:8080
  - service: http_status:404
EOF
```

6. **Run as service:**
```bash
sudo cloudflared service install
sudo systemctl start cloudflared
```

Now access via `https://signals.yourdomain.com` — no port 8080 exposed, no certificate management.

---

## Step 12 — Monitoring & Maintenance

### View Logs
```bash
# Live logs
docker compose logs -f app

# Last 100 lines
docker compose logs --tail=100 app
```

### Check Resource Usage
```bash
# VM resources
free -h
df -h
top

# Docker stats
docker stats
```

### Update Application
```bash
cd ~/apps/market-signals
git pull
docker compose down
docker compose up -d --build
```

### Backup Database
```bash
# Create backup
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup_$(date +%F).sql

# Transfer to local
scp ubuntu@YOUR.IP:~/apps/market-signals/backup_*.sql .
```

### Restart Everything
```bash
docker compose restart
```

---

## Security Checklist

- [ ] SSH key only (no password auth)
- [ ] Firewall: Port 22 restricted to your IP only
- [ ] Firewall: Port 8080 restricted or behind reverse proxy
- [ ] `.env` file has `0600` permissions
- [ ] No secrets committed to git (`.env` in `.gitignore`)
- [ ] IG credentials use demo account initially
- [ ] Auto-trading (`TRADING_AUTO_EXECUTION_ENABLED`) stays `false`
- [ ] Regular updates: `sudo apt update && sudo apt upgrade -y`

---

## Troubleshooting

### Can't connect to VM
```bash
# Check security list has port 22 open
# Check instance is "Running" in console
# Try: ssh -v -i key user@ip (verbose mode)
```

### App starts but can't reach from internet
```bash
# Check security list has port 8080 open
# Check SERVER_ADDRESS=0.0.0.0 is set
# Check: curl http://localhost:8080/actuator/health (from VM)
```

### Out of memory
```bash
# ARM instance has 24GB RAM total — you've allocated plenty
# Check: free -h
# If needed, add swap: sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
```

### IG data allowance exhausted
See `docs/troubleshooting.md` — reduce polling frequency or disable some IG instruments.

---

## Cost Reality Check

| Component | Cost |
|-----------|------|
| Oracle Cloud VM (ARM 1 OCPU, 6GB) | **$0 forever** |
| Block storage (50GB) | **$0 forever** |
| Data transfer (10TB/month) | **$0 forever** |
| **Total** | **$0** |

Claude AI enrichment (optional): ~$5/month paid to Anthropic directly.

---

## Next Steps After Deployment

1. **Paper trade log** — Continue logging signals for 4–8 weeks
2. **Telegram bot commands** — Implement `/position`, `/close`, `/status` for remote control
3. **Enable Claude AI** — Add `CLAUDE_API_KEY` to `.env` and restart
4. **Monitoring** — Set up uptime checking (UptimeRobot free tier pings every 5 min)

---

*Last updated: April 2026*
