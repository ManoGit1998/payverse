# Load tests (k6)

```bash
make e2e        # or any running stack (Docker or make run-*)
make load       # seeds 20 users with INR 5,000 each, then runs payverse.js in grafana/k6 (Docker)
RATE=20 BROWSE_RATE=60 DURATION=5m USERS=40 make load
```

`seed.py` signs users up through auth-service directly (the gateway allows 5 OTP/PIN calls per minute per IP),
passes min KYC and adds money; tokens go to `.users.json` (git-ignored, valid 15 minutes).

| Scenario | Executor | What |
|---|---|---|
| `browse` | constant arrival rate (`BROWSE_RATE`, default 30/s) | wallet, history, notifications, rewards |
| `transfers` | ramping arrival rate up to `RATE` (default 10/s) | P2P by UPI ID, INR 1–5, Idempotency-Key + PIN — the full saga incl. fraud scoring |

Thresholds (the run fails if missed): reads p95 < 400 ms and < 1% errors; transfers p95 < 1.5 s and > 99%
SUCCESS. Summary: `load-tests/results/summary.json`. Watch **Grafana → PayVerse** while it runs.

Baseline on an M-series laptop, whole stack in Docker (12 services × 640 MiB limit): 2,040 transfers at ~10/s with
100% SUCCESS and p95 122 ms; 5,400 reads with p95 14 ms; ledger integrity healthy afterwards.
MIN_KYC limits (INR 10,000 balance / daily debit) cap how long one seed can sustain transfers.
