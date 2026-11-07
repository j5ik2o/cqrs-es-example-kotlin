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
SERVICES=(
  localstack
  mysql
  migration
  dynamodb-setup
  write-api-server-1
  read-model-updater-1
  read-api-server-1
)

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "e2e failed with exit code ${status}; collecting compose logs" >&2
    "${COMPOSE[@]}" logs --no-color --tail=200 || true
  fi
  "${COMPOSE[@]}" down -v --remove-orphans || true
  exit "$status"
}
trap cleanup EXIT

echo "Building the application image"
./tools/scripts/docker-build.sh

echo "Building the e2e test image"
docker build -t e2e-test-kotlin:latest -f tools/e2e-test/Dockerfile tools/e2e-test

"${COMPOSE[@]}" down -v --remove-orphans
"${COMPOSE[@]}" up --remove-orphans --force-recreate --renew-anon-volumes -d "${SERVICES[@]}"

echo "Running the end-to-end verification"
"${COMPOSE[@]}" run --rm --no-deps e2e-test

rmu_duplicates_before=$(
  "${COMPOSE[@]}" logs --no-color read-model-updater-1 |
    grep -cE "Duplicate entry|SQLIntegrityConstraintViolationException" || true
)
rmu_duplicates_before=${rmu_duplicates_before:-0}

echo "Restarting the read model updater to verify head stream replay"
"${COMPOSE[@]}" restart read-model-updater-1
"${COMPOSE[@]}" run --rm --no-deps e2e-test

rmu_duplicates_after=$(
  "${COMPOSE[@]}" logs --no-color read-model-updater-1 |
    grep -cE "Duplicate entry|SQLIntegrityConstraintViolationException" || true
)
rmu_duplicates_after=${rmu_duplicates_after:-0}

if [ "${rmu_duplicates_after}" -gt "${rmu_duplicates_before}" ]; then
  echo "Error: the read model updater applied a duplicate after the restart (${rmu_duplicates_before} -> ${rmu_duplicates_after})" >&2
  exit 1
fi

echo "End-to-end verification and restart replay succeeded"
