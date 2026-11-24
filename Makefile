export JAVA_HOME ?= /usr/lib/jvm/java-25-openjdk
MVN := $(shell if [ -x ./mvnw ]; then echo ./mvnw; else echo mvn; fi)
JAR := target/review-classifier-1.0.0.jar
PORT ?= 8080

.DEFAULT_GOAL := help
.PHONY: help setup install-hooks build package run format format-check lint test coverage check smoke docker-build docker-up docker-down clean

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

setup: install-hooks ## Install git hooks, prepare .env, and resolve Maven dependencies
	@cp -n .env.example .env 2>/dev/null || true
	$(MVN) -B dependency:go-offline
	@echo "Setup complete. Run 'make check' or 'make run'."

install-hooks: ## Configure Git to use .githooks/pre-commit
	@chmod +x .githooks/pre-commit
	@if [ -d .git ]; then git config core.hooksPath .githooks; fi

build: ## Compile main and test sources
	$(MVN) -B test-compile

package: ## Build executable fat-JAR (skipping tests)
	$(MVN) -B package -DskipTests

run: ## Build if needed and run HTTP server locally
	@if [ ! -f $(JAR) ]; then $(MVN) -B package -DskipTests; fi
	MODEL_PATH=data/sentiment_v1.json MODEL_SHA256_PATH=data/sentiment_v1.json.sha256 APP_PORT=$(PORT) $(JAVA_HOME)/bin/java -jar $(JAR)

format: ## Auto-format Java sources with Spotless
	$(MVN) -B spotless:apply

format-check: ## Verify formatting without modifying files
	$(MVN) -B spotless:check

lint: ## Run Spotless, Checkstyle, PMD, and SpotBugs
	$(MVN) -B test-compile spotless:check checkstyle:check pmd:check spotbugs:check

test: ## Run all JUnit 5 tests
	$(MVN) -B test

coverage: ## Run tests, generate JaCoCo report, and verify >= 80% coverage gate
	$(MVN) -B test jacoco:report jacoco:check

check: ## Full quality gate: compile + format + lint + static analysis + tests + JaCoCo + package
	$(MVN) -B verify

smoke: ## Send live curl smoke requests to http://localhost:$(PORT)
	@echo "Checking /health/live ..."
	@curl -sf http://localhost:$(PORT)/health/live && echo ""
	@echo "Checking /health/ready ..."
	@curl -sf http://localhost:$(PORT)/health/ready && echo ""
	@echo "Checking POST /api/v1/reviews/classify (valid with PII) ..."
	@curl -sf -X POST http://localhost:$(PORT)/api/v1/reviews/classify \
		-H 'Content-Type: application/json' \
		-d '{"reviewId":"550e8400-e29b-41d4-a716-446655440001","orderId":"ORD-1001","rating":1,"text":"Товар ужасный, сломался через день! Иван, +7-999-123-45-67","createdAt":"2026-11-24T00:00:00Z"}' && echo ""
	@echo "Checking POST /api/v1/reviews/classify (invalid rating -> HTTP 400) ..."
	@curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST http://localhost:$(PORT)/api/v1/reviews/classify \
		-H 'Content-Type: application/json' \
		-d '{"reviewId":"550e8400-e29b-41d4-a716-446655440002","orderId":"ORD-1002","rating":9,"text":"Тест","createdAt":"2026-11-24T00:00:00Z"}'
	@echo "Checking /metrics ..."
	@curl -sf http://localhost:$(PORT)/metrics | head -n 12

docker-build: ## Build production Docker image
	docker compose build

docker-up: ## Start containerized service
	docker compose up -d

docker-down: ## Stop containerized service
	docker compose down

clean: ## Clean build artifacts and logs
	$(MVN) -B clean
	rm -rf logs/
