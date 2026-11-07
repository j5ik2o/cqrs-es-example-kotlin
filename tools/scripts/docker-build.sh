#!/usr/bin/env bash
set -eu

cd "$(dirname "$0")/../.."

docker build -t cqrs-es-example-kotlin:e2e -f Dockerfile .
