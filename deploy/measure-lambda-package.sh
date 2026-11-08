#!/usr/bin/env bash
#
# Measures the RMU Lambda deployment package and prints a report:
#
#   - compressed ZIP size and expanded size (bytes and MiB)
#   - the largest JARs under lib/ in the expanded package
#   - whether the GCP/SQLite dependencies that ESA Java pulls in are present
#
# The report is informational. This script does not enforce a size threshold.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_ZIP="${REPO_ROOT}/rmu/build/distributions/rmu-lambda.zip"
ZIP="$DEFAULT_ZIP"
TOP="20"
SKIP_BUILD="false"

usage() {
  cat <<'USAGE'
Usage: measure-lambda-package.sh [options]

Options:
  --zip <path>     ZIP to measure. Defaults to rmu/build/distributions/rmu-lambda.zip.
  --top <n>        Number of JARs to list. Defaults to 20.
  --skip-build     Reuse an existing ZIP instead of running Gradle first.
  -h, --help       Show this help.

With no --skip-build, the script runs :rmu:lambdaZip with JDK 17 before
measuring. The expanded size is the sum of the file sizes after unzip, and the
JAR list is sorted by expanded size. No pass/fail threshold is applied.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip)
      ZIP="$2"
      shift 2
      ;;
    --top)
      TOP="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! [[ "$TOP" =~ ^[1-9][0-9]*$ ]]; then
  echo "--top must be a positive integer: $TOP" >&2
  exit 2
fi

if [[ "$SKIP_BUILD" != "true" ]]; then
  "${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :rmu:lambdaZip
  if [[ "$ZIP" != "$DEFAULT_ZIP" ]]; then
    mkdir -p "$(dirname "$ZIP")"
    cp "$DEFAULT_ZIP" "$ZIP"
  fi
fi

if [[ ! -f "$ZIP" ]]; then
  echo "lambda package not found: $ZIP" >&2
  echo "run without --skip-build, or pass --zip <path>" >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/rmu-lambda-size.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ZIP" -d "$WORK"
if [[ ! -d "$WORK/lib" ]]; then
  echo "lib/ not found in the package: $ZIP" >&2
  exit 2
fi

mib() {
  awk -v bytes="$1" 'BEGIN { printf "%.2f", bytes / 1048576 }'
}

COMPRESSED_BYTES="$(wc -c <"$ZIP" | tr -d ' ')"
EXPANDED_BYTES="$(find "$WORK" -type f -exec wc -c {} + | awk '$2 != "total" { sum += $1 } END { print sum + 0 }')"
FILE_COUNT="$(find "$WORK" -type f | wc -l | tr -d ' ')"
JAR_COUNT="$(find "$WORK/lib" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ')"

# One "size path" line per JAR, relative to lib/. wc prints a "total" line when
# it receives more than one file; the awk filters below drop it.
JAR_SIZES="$(cd "$WORK/lib" && find . -maxdepth 1 -name '*.jar' -exec wc -c {} +)"
JAR_BYTES="$(printf '%s\n' "$JAR_SIZES" | awk '$2 != "total" { sum += $1 } END { print sum + 0 }')"

cat <<EOF
== RMU Lambda package ==
zip:        $ZIP
compressed: $COMPRESSED_BYTES bytes ($(mib "$COMPRESSED_BYTES") MiB)
expanded:   $EXPANDED_BYTES bytes ($(mib "$EXPANDED_BYTES") MiB)
files:      $FILE_COUNT ($JAR_COUNT JARs in lib/, $JAR_BYTES bytes / $(mib "$JAR_BYTES") MiB)

== Top $TOP JARs in lib/ by expanded size ==
EOF
printf '%s\n' "$JAR_SIZES" |
  awk '$2 != "total" { printf "%10d %8.2f MiB  %s\n", $1, $1 / 1048576, $2 }' |
  sort -rn |
  awk -v limit="$TOP" 'NR <= limit'

echo
echo "== ESA Java GCP/SQLite dependencies =="
echo "source: io.github.j5ik2o:event-store-adapter-java (transitive via event-store-adapter-kotlin)"
for artifact in sqlite-jdbc google-cloud-spanner google-cloud-bigtable; do
  matches="$(printf '%s\n' "$JAR_SIZES" | awk -v name="$artifact" '$2 != "total" && $2 ~ ("/" name "-[^/]*[.]jar$")')"
  if [[ -n "$matches" ]]; then
    printf '%s\n' "$matches" | awk '{ printf "present: %8.2f MiB  %s\n", $1 / 1048576, $2 }'
  else
    echo "absent:  $artifact"
  fi
done

# Group the Spanner/Bigtable/SQLite artifacts with the gRPC, GCP auth, protobuf
# and crypto runtime they drag in. Keep this in sync with the note in
# docs/LAMBDA_PACKAGE_SIZE.ja.md.
GCP_GROUP_PATTERN='(^|/)(sqlite-jdbc|google-cloud|grpc-google-cloud|proto-google-cloud|grpc-gcp|gax|google-auth-library|google-http-client|api-common|grpc-|opencensus|proto-google-|protobuf-java|tink|conscrypt|bcprov|re2j|dev[.]cel|threeten-extra|perfmark|animal-sniffer|checker-qual)[^/]*[.]jar$'
read -r GCP_COUNT GCP_BYTES < <(
  printf '%s\n' "$JAR_SIZES" | awk -v pat="$GCP_GROUP_PATTERN" '
    $2 != "total" && $2 ~ pat { count += 1; bytes += $1 }
    END { print count + 0, bytes + 0 }
  '
)
GCP_BYTES="${GCP_BYTES:-0}"
GCP_MIB="$(mib "$GCP_BYTES")"
if [[ "$JAR_BYTES" -gt 0 ]]; then
  GCP_SHARE="$(awk -v part="$GCP_BYTES" -v whole="$JAR_BYTES" 'BEGIN { printf "%.1f", part * 100 / whole }')"
else
  GCP_SHARE="0.0"
fi
echo "group:  $GCP_COUNT JARs, $GCP_BYTES bytes ($GCP_MIB MiB, ${GCP_SHARE}% of lib/)"
