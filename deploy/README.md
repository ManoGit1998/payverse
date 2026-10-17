# Deploying PayVerse to Kubernetes

`deploy/helm/payverse` is one chart for the whole platform; `deploy/k8s/payverse-dev.yaml` is the same chart
rendered for `kubectl apply` users (`make k8s-render`).

## What the chart creates

| Per service (12, from `values.services`) | Shared |
|---|---|
| Deployment: non-root (10001), read-only root FS, all capabilities dropped, seccomp `RuntimeDefault`, `/tmp` emptyDir | ConfigMap with infra endpoints and in-cluster service URLs (D8) |
| Probes: startup + liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness` | Secret (generated once, `resource-policy: keep`) or `secrets.existingSecret` |
| `preStop: sleep 5` + 40 s grace (Spring graceful shutdown is 20 s) | Ingress → api-gateway only (`/internal/**` is never routed) |
| Service, PodDisruptionBudget (when replicas > 1), optional HPA (CPU) | NetworkPolicies: same-release traffic only; the gateway also from the ingress controller |
| Heap = 40% of the memory limit (`MaxRAMPercentage`), metaspace capped at 224 MiB | Optional dev infra: Postgres (+ DB-per-service init), Redis (noeviction), Kafka KRaft (+ topics Job), otel-lgtm with the PayVerse dashboards |

## Dev cluster (OrbStack / kind / minikube)

```bash
# images: build locally (make build && docker compose build) and load them, or point image.registry at GHCR (CI pushes there)
helm install payverse deploy/helm/payverse -n payverse --create-namespace \
  --set image.registry=payverse --set image.tag=local --set image.pullPolicy=Never
kubectl -n payverse get pods -w
kubectl -n payverse port-forward svc/payverse-api-gateway 8080:8080
kubectl -n payverse port-forward svc/payverse-lgtm 3000:3000        # Grafana, PayVerse folder
```

Plain manifests instead of Helm: `deploy/k8s/create-secret.sh payverse && kubectl apply -n payverse -f deploy/k8s/payverse-dev.yaml`.

## Production checklist

- `infra.enabled=false` and `external.*` pointing at managed Postgres (one database + role per service, see
  `files/01-create-databases.sh`), Redis (`maxmemory-policy noeviction`, AUTH) and Kafka (create topics with
  `files/create-topics.sh`; replication factor ≥ 3).
- `secrets.existingSecret` from your secret manager. **Back up `PAYVERSE_CRYPTO_*`**: PII is encrypted with them.
- `image.registry=ghcr.io/<owner>` and an immutable `image.tag` (the commit SHA CI pushes).
- Replicas ≥ 2 for every service (or `autoscaling.enabled`), real `ingress.host` + TLS, `config.corsAllowedOrigins`.
- The gateway runs with `SERVER_FORWARD_HEADERS_STRATEGY=framework`: rate limits key anonymous clients on
  `X-Forwarded-For`. The ingress must **overwrite** that header with the client address (ingress-nginx does by
  default; do not enable `compute-full-forwarded-for` or `use-forwarded-headers` unless a trusted proxy sits in front).
- `config.otpSender` and every `Mock*` adapter are simulations: production needs real SMS, PG, UPI and biller adapters.

## Validation

`make helm-lint` lints the chart and validates the rendered manifests against Kubernetes 1.31 schemas
(kubeconform), using Docker images — no local helm needed. CI runs the same checks and fails if
`deploy/k8s/payverse-dev.yaml` is out of date.
