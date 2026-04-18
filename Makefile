.PHONY: up down logs test test-anomaly ps clean pnl-report

up:
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
