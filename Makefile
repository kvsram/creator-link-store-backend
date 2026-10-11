# Local developer commands. `make` with no target prints this list.
# Requirements: Docker Desktop, Java 21, Maven 3.9 (python3 for the smoke test; macOS and Ubuntu have it).

.DEFAULT_GOAL := help
SHELL := /bin/bash

# Development-only values; they match docker-compose.yml. Production secrets live in AWS Secrets Manager.
export JWT_SECRET ?= local-development-secret-at-least-32-characters-long
export ADMIN_BOOTSTRAP_EMAIL ?= admin@example.test
export ADMIN_BOOTSTRAP_PASSWORD ?= local-admin-password-123
export SPRING_PROFILES_ACTIVE ?= local

.PHONY: help up down db run verify smoke logs clean

help:            ## show this help
	@grep -E '^[a-z]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  make %-8s %s\n", $$1, $$2}'

up:              ## build the API image and start PostgreSQL + Redis + API with the demo seed
	docker compose up -d --build

down:            ## stop everything (data volume is kept; `make clean` removes it)
	docker compose down

db:              ## start only PostgreSQL + Redis (then `make run` or run from the IDE)
	docker compose up -d db redis

run:             ## run the API from source against `make db`, with the local profile and demo seed
	mvn -q spring-boot:run

verify:          ## compile, unit tests, architecture rules, integration test (needs Docker)
	mvn -q verify

smoke:           ## walk signup -> login -> profile -> refresh -> logout -> admin against BASE_URL (default localhost:8080)
	scripts/smoke.sh

logs:            ## follow the API container log (the SMS verification links are printed here)
	docker compose logs -f api

clean:           ## stop everything and delete the local database volume
	docker compose down -v
