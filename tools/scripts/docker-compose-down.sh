#!/usr/bin/env bash
set -eu

cd "$(dirname "$0")/../.."

PROJECT=cqrs-es-example-kotlin
COMPOSE=(
  docker compose
  -p "$PROJECT"
  -f tools/docker-compose/docker-compose-databases.yml
  -f tools/docker-compose/docker-compose-applications.yml
  -f tools/docker-compose/docker-compose-e2e-test.yml
)

"${COMPOSE[@]}" down -v --remove-orphans
