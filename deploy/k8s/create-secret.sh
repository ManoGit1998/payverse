#!/usr/bin/env bash
# Creates the payverse-secrets Secret with fresh random values (namespace: $1, default payverse).
# Run once per environment: the crypto keys encrypt PII at rest — losing them makes stored data unreadable.
set -euo pipefail
NS="${1:-payverse}"
kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"
if kubectl -n "$NS" get secret payverse-secrets >/dev/null 2>&1; then
  echo "secret payverse-secrets already exists in $NS — leaving it alone"; exit 0
fi
kubectl -n "$NS" create secret generic payverse-secrets \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -hex 16)" \
  --from-literal=SERVICE_DB_PASSWORD="$(openssl rand -hex 16)" \
  --from-literal=REDIS_PASSWORD="$(openssl rand -hex 16)" \
  --from-literal=SERVICE_CLIENT_SECRET="$(openssl rand -hex 24)" \
  --from-literal=PAYVERSE_CRYPTO_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  --from-literal=PAYVERSE_CRYPTO_BLIND_INDEX_KEY="$(openssl rand -base64 32)" \
  --from-literal=ADMIN_MOBILES="${ADMIN_MOBILES:-9000000001}" \
  --from-literal=SUPPORT_MOBILES="${SUPPORT_MOBILES:-9000000002}"
echo "created secret payverse-secrets in $NS"
