#!/bin/sh
set -eu

AWS_DEFAULT_REGION=${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-northeast-1}}
export AWS_DEFAULT_REGION
export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-x}
export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-x}
export AWS_PAGER=""

DYNAMODB_ENDPOINT=${DYNAMODB_ENDPOINT:-localhost:4566}
PREFIX=${PREFIX:-}
JOURNAL_TABLE_NAME=${JOURNAL_TABLE_NAME:-"${PREFIX:+${PREFIX}-}journal"}
SNAPSHOT_TABLE_NAME=${SNAPSHOT_TABLE_NAME:-"${PREFIX:+${PREFIX}-}snapshot"}
HEAD_TABLE_NAME=${HEAD_TABLE_NAME:-"${PREFIX:+${PREFIX}-}head"}
SNAPSHOT_AID_INDEX_NAME=${SNAPSHOT_AID_INDEX_NAME:-"${PREFIX:+${PREFIX}-}snapshot-aid-index"}

echo "DYNAMODB_ENDPOINT = ${DYNAMODB_ENDPOINT}"
echo "JOURNAL_TABLE_NAME = ${JOURNAL_TABLE_NAME}"
echo "SNAPSHOT_TABLE_NAME = ${SNAPSHOT_TABLE_NAME}"
echo "HEAD_TABLE_NAME = ${HEAD_TABLE_NAME}"
echo "SNAPSHOT_AID_INDEX_NAME = ${SNAPSHOT_AID_INDEX_NAME}"

ENDPOINT_URL_OPTION="--endpoint-url http://${DYNAMODB_ENDPOINT}"

# journal is an append-only log. The read model updater does not subscribe to
# its Streams: they do not preserve the order of an aggregate's events.
aws dynamodb create-table \
  ${ENDPOINT_URL_OPTION} \
  --table-name "${JOURNAL_TABLE_NAME}" \
  --attribute-definitions \
  AttributeName=aid,AttributeType=S \
  AttributeName=seq_nr,AttributeType=N \
  --key-schema \
  AttributeName=aid,KeyType=HASH \
  AttributeName=seq_nr,KeyType=RANGE \
  --provisioned-throughput \
  ReadCapacityUnits=10,WriteCapacityUnits=10

aws dynamodb create-table \
  ${ENDPOINT_URL_OPTION} \
  --table-name "${SNAPSHOT_TABLE_NAME}" \
  --attribute-definitions \
  AttributeName=aid,AttributeType=S \
  AttributeName=skey,AttributeType=N \
  AttributeName=active_history_seq_nr,AttributeType=N \
  --key-schema \
  AttributeName=aid,KeyType=HASH \
  AttributeName=skey,KeyType=RANGE \
  --provisioned-throughput \
  ReadCapacityUnits=10,WriteCapacityUnits=10 \
  --global-secondary-indexes \
  "[
    {
      \"IndexName\": \"${SNAPSHOT_AID_INDEX_NAME}\",
      \"KeySchema\": [{\"AttributeName\":\"aid\",\"KeyType\":\"HASH\"},
                      {\"AttributeName\":\"active_history_seq_nr\",\"KeyType\":\"RANGE\"}],
      \"Projection\":{\"ProjectionType\":\"KEYS_ONLY\"},
      \"ProvisionedThroughput\":{\"ReadCapacityUnits\":10,\"WriteCapacityUnits\":10}
    }
  ]"

# head is the change-feed unit for the read model updater.
aws dynamodb create-table \
  ${ENDPOINT_URL_OPTION} \
  --table-name "${HEAD_TABLE_NAME}" \
  --attribute-definitions \
  AttributeName=aid,AttributeType=S \
  --key-schema \
  AttributeName=aid,KeyType=HASH \
  --provisioned-throughput \
  ReadCapacityUnits=10,WriteCapacityUnits=10 \
  --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE
