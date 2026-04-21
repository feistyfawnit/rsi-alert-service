#!/bin/bash
#
# SSH into EC2 and view app logs
# Usage: ./logs.sh [tail|follow|last N]
#

set -e

EC2_IP="108.128.230.238"
SSH_KEY="${HOME}/.ssh/market-signals.pem"

if [ ! -f "$SSH_KEY" ]; then
    echo "❌ SSH key not found: $SSH_KEY"
    exit 1
fi

# Parse args
MODE="${1:-follow}"  # default: follow
LINES="${2:-100}"

echo "📜 Connecting to logs on ${EC2_IP}..."

if [ "$MODE" == "follow" ] || [ "$MODE" == "f" ]; then
    echo "🔴 Following logs (Ctrl+C to exit)..."
    ssh -i "$SSH_KEY" ubuntu@${EC2_IP} 'cd ~/apps/market-signals && docker-compose logs -f app'
elif [ "$MODE" == "last" ] || [ "$MODE" == "tail" ]; then
    echo "📄 Last ${LINES} lines:"
    ssh -i "$SSH_KEY" ubuntu@${EC2_IP} "cd ~/apps/market-signals && docker-compose logs --tail ${LINES} app"
else
    echo "Usage: ./logs.sh [follow|last N]"
    echo "  ./logs.sh follow     # Follow logs live (default)"
    echo "  ./logs.sh last 50    # Show last 50 lines"
    exit 1
fi
