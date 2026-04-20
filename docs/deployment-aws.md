# Deploy to AWS Free Tier

*EC2 running, 12 months free, CV-value for Irish market*

> **⚠️ Region Note:** This guide uses **us-west-2** (Oregon) by default in the AWS console. For EU-based deployment, manually select **eu-west-1** (Dublin) for ~5ms latency from Ireland. The steps are identical — only the region changes.
>
> **Alternative options:** See `docs/archived/deployment-alibaba-cloud.md` (simpler console, cheaper after free tier) or `docs/archived/deployment-oracle-cloud.md` (truly free forever, but complex). AWS chosen here for CV value in the Irish market.

---

## Why AWS for this project

- **Dublin Region option** — Select **eu-west-1** for ~5ms latency from Ireland and EU data residency. We used **us-west-2** (Oregon) in this walkthrough — steps are identical, just the region dropdown changes.
- **CV value** — Every Irish company uses AWS; `EC2`, `Security Groups`, `CloudWatch` on your resume
- **You already know it** — Terraform experience at HMH, Egenera background
- **12 months free** — t3.micro (2 vCPU, 1GB RAM) + 30GB storage
- **Better ecosystem** — Terraform, CloudFormation, CDK all first-class

**Trade-off:** More console clicking than Alibaba, but you know the concepts (VPC, SG, IAM).

---

## Architecture (Simple)

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Internet  │────▶│  Security    │────▶│  EC2        │
│             │     │  Group       │     │  t2.micro   │
└─────────────┘     │  (22, 8080)  │     │  Dublin     │
                    └──────────────┘     │             │
                                          │  Docker     │
                                          │  ┌────────┐ │
                                          │  │  App   │ │
                                          │  │  8080  │ │
                                          │  └────────┘ │
                                          │  ┌────────┐ │
                                          │  │Postgres│ │
                                          │  │  5432  │ │
                                          │  └────────┘ │
                                          └─────────────┘
```

**No RDS** — too expensive for free tier. PostgreSQL runs in Docker on the same instance (saves ~€15/month).

---

## Prerequisites

- AWS account (free tier eligible)
- SSH key pair downloaded to your machine (e.g., `~/Downloads/market-signals.pem`)
- Telegram bot token
- (Optional) IG API credentials

**⚠️ SSH Key Location:** When you create the key pair in AWS, it downloads as `.pem`. Move it to `~/.ssh/` and set permissions:
```bash
mv ~/Downloads/market-signals.pem ~/.ssh/
chmod 600 ~/.ssh/market-signals.pem
```

---

## Step 1 — Create EC2 Instance

1. Console → **EC2** → **Instances** → **Launch Instances**
2. Configure:

| Setting | Value |
|---------|-------|
| Name | `market-signals` |
| AMI | **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type** — Free tier eligible |
| Instance Type | **t3.micro** — Free tier eligible (select from dropdown, don't use t2.micro) |
| Key Pair | Create new or select existing |
| Network Settings | **Create security group** (see Step 2) |
| Storage | **30 GB gp3** (click "Configure storage", change from default 8GB to 30GB) |
| Advanced → User Data | (Optional) Paste bootstrap script below |

**Important: SSH Security (Critical)**

The console defaults to `Anywhere 0.0.0.0/0` for SSH — **change this before launching**:
1. In the **Network settings** section, find **Allow SSH traffic from**
2. Change from **Anywhere** to **My IP** (dropdown auto-populates your current IP)
3. If you already launched with Anywhere: **EC2 → Instances → market-signals → Security → Security groups → Edit inbound rules → change SSH source to My IP**

**⚠️ Permission denied (publickey) fix:** If SSH fails, your key file is probably in `~/Downloads/`. Move it:
```bash
mv ~/Downloads/market-signals.pem ~/.ssh/
chmod 600 ~/.ssh/market-signals.pem
ssh -i ~/.ssh/market-signals.pem ubuntu@YOUR.ELASTIC.IP
```

**Bootstrap script** (optional — runs on first boot):
```bash
#!/bin/bash
apt update
apt install -y docker.io docker-compose
usermod -aG docker ubuntu
systemctl enable docker
```

3. Click **Launch Instance**
4. **Wait for Status Check to show "2/2 checks passed"** (takes 1–2 minutes)

---

## Step 2 — Security Group Configuration (Post-Launch Fixes)

If you launched with SSH set to "Anywhere", fix it now. Also add port 8080 for the application.

1. **EC2 → Instances → market-signals → Security → Security groups** (click `launch-wizard-1`)
2. **Inbound rules → Edit inbound rules**

**Fix SSH (if set to Anywhere):**
- Find the SSH rule with `0.0.0.0/0`
- Change **Source** to **My IP** (dropdown auto-populates)

**Add Application Port:**
- **Add rule**
- Type: **Custom TCP**
- Port range: **8080**
- Source: **My IP** (restrict to your IP only)
- Description: `Application API`

3. **Save rules**

> 🔒 **Security note:** Keep 8080 restricted to your IP unless you need external access (e.g., Telegram webhooks).

---

## Step 3 — Allocate Elastic IP (Static IP)

AWS assigns random IPs on reboot. You want a stable IP for your Telegram bot.

1. **EC2** → **Network & Security** → **Elastic IPs**
2. Click **Allocate Elastic IP address**
3. Select **Amazon's pool of IPv4 addresses**
4. Click **Allocate**
5. Select the IP → **Actions** → **Associate Elastic IP address**
6. Select your instance → **Associate**

**Note the Elastic IP** — this is your permanent address.

---

## Step 4 — Connect & Setup

```bash
# SSH to instance
ssh -i ~/.ssh/aws_key.pem ubuntu@YOUR.ELASTIC.IP

# Verify Docker (if you didn't use user data)
docker --version || {
  sudo apt update
  sudo apt install -y docker.io docker-compose
  sudo usermod -aG docker $USER
  sudo systemctl enable docker
  newgrp docker
}
```

---

## Step 5 — Deploy Application

```bash
# Setup directory
mkdir -p ~/apps && cd ~/apps

# Clone repo
git clone https://github.com/yourusername/rsi-alert-service.git
cd rsi-alert-service
```

### Transfer .env

**From your Mac:**
```bash
scp -i ~/.ssh/aws_key.pem /Users/terryi/Windsurf/rsi-alert-service/.env ubuntu@YOUR.IP:/home/ubuntu/apps/rsi-alert-service/
```

**Edit on server:**
```bash
nano .env
```

Required additions for AWS deployment:
```bash
# Existing variables
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_IDS=...
IG_ENABLED=false

# NEW: Bind to all interfaces for remote access
SERVER_ADDRESS=0.0.0.0
```

### Start Services

```bash
docker-compose up -d --build

# Verify
docker-compose ps
docker-compose logs -f app
```

### Test from your Mac

```bash
curl http://YOUR.ELASTIC.IP:8080/actuator/health
curl http://YOUR.ELASTIC.IP:8080/api/instruments/enabled
```

---

## Step 6 — Handle 1GB RAM Constraint

t2.micro has 1GB RAM. Java + PostgreSQL will OOM without swap.

```bash
# Create 2GB swapfile
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# Persist across reboots
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Verify
free -h
swapon -s
```

**Optional:** Limit JVM heap in `docker-compose.yml`:
```yaml
environment:
  JAVA_OPTS: "-Xmx512m -Xms256m"
```

---

## Step 7 — (Optional) Free SSL with Let's Encrypt

If you want HTTPS on port 443 (cleaner, can remove 8080 from security group):

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

# Configure nginx reverse proxy
sudo tee /etc/nginx/sites-available/market-signals << 'EOF'
server {
    listen 80;
    server_name signals.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/market-signals /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl restart nginx

# Get SSL (requires domain pointing to your Elastic IP)
sudo certbot --nginx -d signals.yourdomain.com
```

Then update Security Group: remove 8080, add 443, restrict 80 to `0.0.0.0/0` (certbot needs it).

---

## Step 8 — CloudWatch Logs (Optional but Good for CV)

Instead of `docker-compose logs`, send logs to CloudWatch:

```bash
# Install CloudWatch agent
wget https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb
sudo dpkg -i amazon-cloudwatch-agent.deb

# Configure
sudo tee /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/home/ubuntu/apps/rsi-alert-service/logs/*.log",
            "log_group_name": "market-signals",
            "log_stream_name": "{instance_id}"
          }
        ]
      }
    }
  }
}
EOF

sudo systemctl enable amazon-cloudwatch-agent
sudo systemctl start amazon-cloudwatch-agent
```

Now view logs in Console → **CloudWatch** → **Log groups** → `market-signals`.

---

## Step 9 — Terraform (Since You Know It)

If you want this in code (good for GitOps), here's a minimal Terraform setup:

**`main.tf`:**
```hcl
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "eu-west-1"  # Dublin
}

resource "aws_security_group" "market_signals" {
  name_prefix = "market-signals"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["YOUR.IP.ADDRESS/32"]  # Your home IP
  }

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["YOUR.IP.ADDRESS/32"]  # Or 0.0.0.0/0 for webhooks
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "market_signals" {
  ami                    = "ami-0c1c30571d2dae5c9"  # Ubuntu 22.04 in eu-west-1
  instance_type          = "t2.micro"
  key_name               = "your-key-pair-name"
  vpc_security_group_ids = [aws_security_group.market_signals.id]
  
  root_block_device {
    volume_size = 30
  }

  user_data = <<-EOF
              #!/bin/bash
              apt update
              apt install -y docker.io docker-compose
              usermod -aG docker ubuntu
              systemctl enable docker
              EOF

  tags = {
    Name = "market-signals"
  }
}

resource "aws_eip" "market_signals" {
  instance = aws_instance.market_signals.id
  domain   = "vpc"
}

output "public_ip" {
  value = aws_eip.market_signals.public_ip
}
```

**Deploy:**
```bash
terraform init
terraform plan
terraform apply
```

**Connect:**
```bash
ssh -i ~/.ssh/aws_key.pem ubuntu@$(terraform output -raw public_ip)
```

---

## Step 10 — Maintenance Commands

```bash
# View logs
docker-compose logs -f app

# Update application
git pull
docker-compose down
docker-compose up -d --build

# Backup database to S3 (optional)
# (Requires AWS CLI + IAM role, or scp locally)
docker exec market-signals-postgres pg_dump -U postgres market_signals > backup.sql
scp backup.sql your-mac:/Users/terryi/Downloads/

# Check resource usage
free -h
df -h
docker stats
```

---

## Cost After Free Tier

| Component | Monthly Cost |
|-----------|-------------|
| t2.micro (or t3.micro) | ~€8-12 |
| 30GB gp2 storage | ~€2-3 |
| Data transfer (reasonable use) | ~€1-2 |
| Elastic IP (attached) | Free |
| **Total** | **~€12-18/month** |

**Note:** After 12 months, evaluate — Alibaba Cloud or Oracle Cloud are cheaper long-term, but migration is trivial.

---

## Troubleshooting

### Can't reach port 8080
```bash
# Check security group allows your IP
# Check .env has SERVER_ADDRESS=0.0.0.0
# Check app is binding: docker exec market-signals-service netstat -tlnp
```

### Out of memory
```bash
# Check swap is active
free -h
swapon -s

# Check OOM kills
dmesg | grep -i "killed process"
```

### Docker permission denied
```bash
# Re-login to pick up docker group
exit
ssh ...
# Or manually:
newgrp docker
```

### Elastic IP charges
Elastic IPs are free **only when attached to a running instance**. If you stop the instance, detach the EIP or you'll pay ~€0.005/hour.

---

## Security Checklist

- [ ] **Security Group: Port 22 and 8080 restricted to your IP (not 0.0.0.0/0)**
- [ ] Elastic IP attached (stable endpoint)
- [ ] Swap file configured (prevents OOM)
- [ ] `.env` file permissions: `chmod 600 .env`
- [ ] No secrets committed: verify `.env` in `.gitignore`
- [ ] IG demo account only (if enabled)
- [ ] TRADING_AUTO_EXECUTION_ENABLED = false
- [ ] Auto-updates: `sudo apt install unattended-upgrades` (optional)

---

## Comparison: Why AWS Wins for You

| Factor | AWS | Alibaba | Oracle |
|--------|-----|---------|--------|
| **Dublin region** | ✅ 5ms latency | ❌ Frankfurt only | ❌ Frankfurt only |
| **CV value in Ireland** | ✅ Every job uses it | ❌ Rare | ❌ Rare |
| **Your experience** | ✅ Terraform at HMH | ❌ New platform | ❌ New platform |
| **Ecosystem** | ✅ Best tooling | ⚠️ Decent | ⚠️ Sparse |
| **Free tier cost** | Same (12 months) | Same | Forever |
| **Post-free cost** | ~€15/mo | ~€4/mo | €0 |
| **Console complexity** | Complex | Simple | Very complex |

**Bottom line:** For someone with Terraform/AWS experience working in Ireland, AWS is the obvious choice despite slightly higher post-free-tier costs. The skills transfer and Dublin latency matter more than €10/month.

---

## Next Steps

1. **Deploy:** Follow Step 1-5 (or use Terraform in Step 9)
2. **Verify:** `curl http://YOUR.IP:8080/actuator/health`
3. **Paper trade:** Continue logging signals in the P&L report
4. **Monitor:** Set up CloudWatch alarm for instance health (optional CV boost)

---

## CI/CD with GitHub Actions (Optional)

Auto-deploy on every push to `main` or `master`:

**`.github/workflows/deploy.yml`:**
```yaml
name: Deploy to EC2

on:
  push:
    branches: [main, master]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd ~/apps/rsi-alert-service
            git pull origin main
            docker-compose down
            docker-compose up -d --build
            docker-compose ps
```

**Setup:**
1. Push code to GitHub (**private repo recommended** — `.env` contains IG credentials)
2. Add secrets: Repository → Settings → Secrets → Actions → New repository secret
   - `EC2_HOST` = `34.218.32.160` (your Elastic IP)
   - `EC2_SSH_KEY` = Contents of `~/.ssh/market-signals.pem`
3. On the EC2 instance, authorize GitHub Actions SSH:
   ```bash
   cat ~/.ssh/authorized_keys  # Ensure your local key is there
   # For GitHub Actions, the same key approach works
   ```
4. Or use HTTPS with Personal Access Token (PAT) instead of SSH for git pull

---

*Last updated: April 2026*
