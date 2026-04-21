#!/bin/bash
#
# Quick deploy script for AWS EC2 instance
# Usage: ./deploy.sh
#

set -e

EC2_IP="108.128.230.238"
SSH_KEY="${HOME}/.ssh/market-signals.pem"

echo "🚀 Deploying to AWS EC2 (${EC2_IP})..."

# Check if key exists
if [ ! -f "$SSH_KEY" ]; then
    echo "❌ SSH key not found: $SSH_KEY"
    exit 1
fi

# SSH to EC2, pull latest, rebuild
echo "📥 Pulling latest code and rebuilding..."
ssh -i "$SSH_KEY" ubuntu@${EC2_IP} << 'REMOTE'
    cd ~/apps/market-signals
    echo "⬇️  Pulling from origin..."
    git pull origin main
    echo "🛑 Stopping current containers..."
    docker-compose down
    echo "🏗️  Building and starting..."
    docker-compose up -d --build
    echo "✅ Done! Checking status..."
    docker-compose ps
REMOTE

echo ""
echo "✅ Deploy complete!"
echo ""
echo "📊 Check health: curl http://${EC2_IP}:8080/actuator/health"
echo "📜 View logs: ssh -i ${SSH_KEY} ubuntu@${EC2_IP} 'docker-compose logs -f app'"
