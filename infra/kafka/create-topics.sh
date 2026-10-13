#!/usr/bin/env bash
# Creates every PayVerse topic and its dead-letter topic (<topic>.dlt).
# Keep in sync with com.payverse.common.events.Topics (EventCatalogTest verifies this file).
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
PARTITIONS="${KAFKA_PARTITIONS:-3}"
KT=/opt/kafka/bin/kafka-topics.sh

echo "Waiting for Kafka at $BOOTSTRAP ..."
until $KT --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do sleep 2; done

TOPICS=(
  payverse.auth.user-registered.v1
  payverse.auth.new-device-login.v1
  payverse.auth.pin-locked.v1
  payverse.user.kyc-upgraded.v1
  payverse.user.bank-account-linked.v1
  payverse.wallet.created.v1
  payverse.wallet.money-added.v1
  payverse.wallet.money-withdrawn.v1
  payverse.wallet.status-changed.v1
  payverse.wallet.ledger-posted.v1
  payverse.transaction.initiated.v1
  payverse.transaction.completed.v1
  payverse.transaction.failed.v1
  payverse.transaction.reversed.v1
  payverse.transaction.money-requested.v1
  payverse.payment.collect-requested.v1
  payverse.payment.pg-charge-settled.v1
  payverse.bill.payment-completed.v1
  payverse.bill.payment-failed.v1
  payverse.bill.mandate-executed.v1
  payverse.rewards.cashback-granted.v1
  payverse.fraud.transaction-flagged.v1
  payverse.admin.refund-approved.v1
  payverse.recon.mismatch-detected.v1
)

for topic in "${TOPICS[@]}"; do
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$topic" \
      --partitions "$PARTITIONS" --replication-factor 1 --config retention.ms=604800000 >/dev/null
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$topic.dlt" \
      --partitions 1 --replication-factor 1 --config retention.ms=1209600000 >/dev/null
  echo "  ok  $topic (+ .dlt)"
done
echo "All topics created."
