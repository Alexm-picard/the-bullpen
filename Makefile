# Makefile — human-facing entry points for The Bullpen.
#
# Most subprojects (backend / training / frontend) have their own native build
# tools (Gradle / uv / npm). This Makefile is the place for cross-cutting
# operations that ADRs 0006 (dev/prod boundary) and 0007 (S3-compatible storage)
# explicitly name: train-sample / train-full / minio-up / minio-down /
# sync-mirror. It also collects the few one-liners worth memorizing.
#
# Convention: targets that only make sense on macOS (MinIO, rclone sync to the
# portable drive) gate on `$(IS_MAC)` and fail loud if you call them elsewhere.
# Targets that only make sense on the WSL2 prod host (deploy, backup) gate on
# `$(IS_LINUX)` similarly.

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.ONESHELL:

UNAME := $(shell uname -s)
IS_MAC   := $(if $(filter Darwin,$(UNAME)),1,0)
IS_LINUX := $(if $(filter Linux,$(UNAME)),1,0)

# ADR-0007 knobs — keep defaults that work without any account so `make help`
# stays useful on a fresh checkout. Prod target is Cloudflare R2 per decision
# [128] (originally B2, reverted before any code was written against B2).
R2_REMOTE       ?= bullpen-r2
R2_BUCKET       ?= bullpen-prod
MINIO_DATA_DIR  ?= /Volumes/MyDrive/bullpen-data
MINIO_PORT      ?= 9000
MINIO_CONSOLE   ?= 9001
MINIO_PIDFILE   ?= /tmp/bullpen-minio.pid

# Repo paths.
REPO_ROOT := $(shell git rev-parse --show-toplevel 2>/dev/null || pwd)

.DEFAULT_GOAL := help

##@ Help

.PHONY: help
help: ## Show this list
	@printf "\n\033[1mThe Bullpen — make targets\033[0m\n\n"
	@awk 'BEGIN{FS=":.*##"; section=""} \
	  /^##@/ { sub(/^##@ */, "", $$0); printf "\033[1m%s\033[0m\n", $$0; next } \
	  /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 } \
	  END { printf "\n" }' $(MAKEFILE_LIST)
	@printf "Detected OS: %s (IS_MAC=%s, IS_LINUX=%s)\n\n" "$(UNAME)" "$(IS_MAC)" "$(IS_LINUX)"

##@ Training (ADR-0006 dev/prod split)

.PHONY: train-sample
train-sample: ## Iterate on a stratified sample locally (Mac, no GPU needed)
	@if [[ ! -d training ]]; then \
	  echo "ERROR: training/ does not exist yet — lands in Phase 2." >&2; exit 1; \
	fi
	@if ! command -v uv >/dev/null 2>&1; then \
	  echo "ERROR: uv not installed (https://docs.astral.sh/uv/getting-started/)" >&2; exit 1; \
	fi
	@if [[ ! -f training/scripts/train_sample.py ]]; then \
	  echo "STUB: training/scripts/train_sample.py not present yet. Per ADR-0006 this target"; \
	  echo "      runs LightGBM on a stratified sample of pitches pulled via 'make sync-mirror'."; \
	  echo "      Lands in Phase 2 alongside the real feature pipeline."; exit 2; \
	fi
	cd training && uv run python -m scripts.train_sample

.PHONY: train-full
train-full: ## Trigger a full training run (Mac side: ssh to desktop; desktop side: local)
	@if [[ "$(IS_MAC)" == "1" ]]; then \
	  echo "STUB: from the MacBook, 'make train-full' would ssh to the WSL2 desktop and"; \
	  echo "      invoke this same target there. Lands when training/ is real (Phase 2)."; \
	  exit 2; \
	fi
	@if [[ ! -f training/scripts/train_full.py ]]; then \
	  echo "STUB: training/scripts/train_full.py not present yet. Phase 2."; exit 2; \
	fi
	cd training && uv run python -m scripts.train_full

##@ Storage (ADR-0007 offline dev)

.PHONY: minio-up
minio-up: ## Start MinIO on the portable drive (Mac only)
	@if [[ "$(IS_MAC)" != "1" ]]; then \
	  echo "ERROR: minio-up is Mac-only (ADR-0007 — offline dev runs on MacBook + portable drive)." >&2; \
	  echo "       Prod uses R2 directly; no local MinIO needed on the WSL2 host." >&2; \
	  exit 1; \
	fi
	@if ! command -v minio >/dev/null 2>&1; then \
	  echo "ERROR: minio not installed. brew install minio/stable/minio" >&2; exit 1; \
	fi
	@if [[ ! -d "$(MINIO_DATA_DIR)" ]]; then \
	  echo "ERROR: $(MINIO_DATA_DIR) not mounted. Plug in the portable drive." >&2; exit 1; \
	fi
	@if [[ -f "$(MINIO_PIDFILE)" ]] && kill -0 "$$(cat $(MINIO_PIDFILE))" 2>/dev/null; then \
	  echo "MinIO already running (pid $$(cat $(MINIO_PIDFILE))). 'make minio-down' to stop."; exit 0; \
	fi
	@echo "Launching MinIO on 127.0.0.1:$(MINIO_PORT) (console :$(MINIO_CONSOLE)) from $(MINIO_DATA_DIR)"
	@MINIO_ROOT_USER=bullpen-dev MINIO_ROOT_PASSWORD=bullpen-dev-secret \
	  nohup minio server --address "127.0.0.1:$(MINIO_PORT)" \
	    --console-address "127.0.0.1:$(MINIO_CONSOLE)" \
	    "$(MINIO_DATA_DIR)" >/tmp/bullpen-minio.log 2>&1 & \
	  echo $$! > "$(MINIO_PIDFILE)"
	@sleep 1 && echo "S3_ENDPOINT_URL=http://127.0.0.1:$(MINIO_PORT)  (set this in your dev env)"

.PHONY: minio-down
minio-down: ## Stop the local MinIO instance
	@if [[ ! -f "$(MINIO_PIDFILE)" ]]; then \
	  echo "MinIO not running (no pid file)."; exit 0; \
	fi
	@PID=$$(cat "$(MINIO_PIDFILE)"); \
	  if kill -0 "$$PID" 2>/dev/null; then \
	    kill "$$PID" && echo "Stopped MinIO (pid $$PID)"; \
	  else \
	    echo "MinIO not running (stale pid file)"; \
	  fi; \
	  rm -f "$(MINIO_PIDFILE)"

.PHONY: sync-mirror
sync-mirror: ## rclone sync R2 → portable drive (Mac only, pre-travel ritual)
	@if [[ "$(IS_MAC)" != "1" ]]; then \
	  echo "ERROR: sync-mirror is Mac-only (the offline mirror lives on the portable drive)." >&2; \
	  exit 1; \
	fi
	@if ! command -v rclone >/dev/null 2>&1; then \
	  echo "ERROR: rclone not installed. brew install rclone" >&2; exit 1; \
	fi
	@if ! rclone listremotes | grep -q "^$(R2_REMOTE):$$"; then \
	  echo "ERROR: rclone remote '$(R2_REMOTE)' not configured." >&2; \
	  echo "       Run: rclone config (choose 's3', provider 'Cloudflare R2'," >&2; \
	  echo "       endpoint https://<account-id>.r2.cloudflarestorage.com," >&2; \
	  echo "       supply R2 access key + secret)." >&2; \
	  exit 1; \
	fi
	@if [[ ! -d "$(MINIO_DATA_DIR)" ]]; then \
	  echo "ERROR: $(MINIO_DATA_DIR) not mounted. Plug in the portable drive." >&2; exit 1; \
	fi
	@echo "Syncing $(R2_REMOTE):$(R2_BUCKET)/samples/dev/ → $(MINIO_DATA_DIR)/samples/dev/"
	rclone sync --progress \
	  "$(R2_REMOTE):$(R2_BUCKET)/samples/dev/" \
	  "$(MINIO_DATA_DIR)/samples/dev/"
	@echo "Mirror updated at $$(date -u +%FT%TZ). Snapshot is travel-ready."

##@ Build / check (cross-subdir)

.PHONY: check
check: ## Run all formatters / linters / tests
	cd backend  && ./gradlew check
	cd training && uv run ruff check && uv run pyright && uv run pytest
	cd frontend && npm run lint && npx tsc --noEmit && npm test --silent

.PHONY: services-up
services-up: ## docker compose up stateful services (ClickHouse, Prometheus, Grafana)
	./infra/clickhouse/render-users.sh --env-file infra/.env
	docker compose -f infra/docker-compose.yml --env-file infra/.env up -d

.PHONY: services-down
services-down: ## docker compose down stateful services
	docker compose -f infra/docker-compose.yml --env-file infra/.env down

##@ Ops (WSL2 prod host only)

.PHONY: deploy
deploy: ## Run deploy.sh (Linux/WSL2 only — prefer /deploy-safely)
	@if [[ "$(IS_LINUX)" != "1" ]]; then \
	  echo "ERROR: deploy is run on the WSL2 host, not the Mac (ADR-0006)." >&2; \
	  echo "       Push to main, then ssh to the desktop and run './deploy.sh' there." >&2; \
	  exit 1; \
	fi
	./deploy.sh

.PHONY: backup
backup: ## Run a USB backup to the BULLPEN_BAK drive (Linux only)
	@if [[ "$(IS_LINUX)" != "1" ]]; then \
	  echo "ERROR: backup is run on the WSL2 host (Layer 2 USB is for the prod box)." >&2; \
	  exit 1; \
	fi
	./infra/backup/usb-backup.sh

.PHONY: drill-reboot
drill-reboot: ## Kick off the reboot drill (CLAUDE.md rule 8)
	@echo "Use the /drill reboot slash command — it captures evidence + writes the report."
	@echo "If you're running this from outside Claude, see .claude/agents/drill-runner.md"
