.PHONY: up down logs test test-anomaly ps clean pnl-report remote-report remote-csv remote-append remote-logs remote-logs-tail remote-health pull-reports ship deploy

EC2_IP  := 108.128.230.238
SSH_KEY := $(HOME)/.ssh/market-signals.pem
SSH     := ssh -i $(SSH_KEY) ubuntu@$(EC2_IP)
APP_DIR := ~/apps/market-signals

up:
	@if [ "$(LOCAL_RUN)" != "yes" ]; then \
		echo ""; \
		echo "⚠️  WARNING: AWS EC2 is the primary instance."; \
		echo "   Running locally AND on AWS will double IG API usage (~900+ extra data points/day)."; \
		echo ""; \
		echo "   To start locally anyway: make up LOCAL_RUN=yes"; \
		echo "   To deploy/watch AWS:     make ship"; \
		echo ""; \
		exit 1; \
	fi
	@colima status > /dev/null 2>&1 || (echo "Starting Colima..." && colima start)
	docker-compose up -d --build

down:
	docker-compose down

logs:
	docker-compose logs -f app

test:
	curl -X POST http://localhost:8080/api/test/notify

test-anomaly:
	curl -X POST "http://localhost:8080/api/test/anomaly?type=polymarket"

ps:
	docker-compose ps

clean:
	docker-compose down -v

pnl-report:
	@mkdir -p reports
	@curl -s http://localhost:8080/api/positions/pnl-report > reports/pnl-report.md
	@echo "P&L report written to reports/pnl-report.md"

deploy:
	@echo "🚀 Deploying to AWS EC2 ($(EC2_IP))..."
	$(SSH) "cd $(APP_DIR) && git fetch origin main && git reset --hard origin/main && docker-compose down && docker-compose up -d --build && docker-compose ps"
	@echo "✅ Deploy complete — run 'make remote-logs' to watch"

remote-logs:
	$(SSH) -t "cd $(APP_DIR) && docker-compose logs -f app"

ship: deploy remote-logs

remote-report:
	@mkdir -p reports
	$(SSH) "curl -s http://localhost:8080/api/positions/pnl-report" > reports/pnl-report.md
	@echo "P&L report pulled from EC2 → reports/pnl-report.md"

remote-csv:
	@mkdir -p reports
	$(SSH) "curl -s http://localhost:8080/api/positions/pnl-report/csv" > reports/signal-outcomes-live.csv
	@echo "CSV pulled from EC2 → reports/signal-outcomes-live.csv"

remote-append:
	@mkdir -p reports
	@echo "" >> reports/signal-outcomes.md
	@echo "---" >> reports/signal-outcomes.md
	@echo "" >> reports/signal-outcomes.md
	@echo "# Signal Outcome Report — $$(date -u '+%b %d %Y %H:%M UTC')" >> reports/signal-outcomes.md
	@echo "" >> reports/signal-outcomes.md
	$(SSH) "curl -s http://localhost:8080/api/positions/pnl-report" >> reports/signal-outcomes.md
	@echo "✅ Appended live EC2 report → reports/signal-outcomes.md"

remote-logs-tail:
	$(SSH) "cd $(APP_DIR) && docker-compose logs --tail $${N:-100} app"

remote-health:
	@echo "🔍 EC2 Health Check ($(EC2_IP))..."
	@$(SSH) "echo '--- Load / Uptime ---' && uptime && echo '' && echo '--- Memory ---' && free -h && echo '' && echo '--- Disk ---' && df -h / && echo '' && echo '--- Docker ---' && docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' && echo '' && echo '--- Container Stats ---' && docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}'"

pull-reports:
	@mkdir -p reports
	@echo "📥 Pulling reports from EC2 ($(EC2_IP)) → ./reports/ ..."
	@scp -i $(SSH_KEY) -r ubuntu@$(EC2_IP):$(APP_DIR)/reports/* reports/ 2>/dev/null || true
	@echo "✅ Reports synced locally."
