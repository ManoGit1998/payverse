// PayVerse k6 load test. Seed users first (python3 load-tests/seed.py), then: make load
//   BASE_URL   gateway (default http://host.docker.internal:8080 — k6 runs in Docker)
//   RATE       peak transfers per second (default 10), BROWSE_RATE reads per second (default 30), DURATION (default 3m)
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const RATE = Number(__ENV.RATE || 10);
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 30);
const DURATION = __ENV.DURATION || '3m';
const seed = JSON.parse(open('./.users.json'));
const users = seed.users;

const transferOutcome = new Counter('payverse_transfer_outcomes');
const transferLatency = new Trend('payverse_transfer_latency', true);

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate', exec: 'browse', rate: BROWSE_RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 20, maxVUs: 60,
    },
    transfers: {
      executor: 'ramping-arrival-rate', exec: 'transfer', startRate: 1, timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 80,
      stages: [{ target: RATE, duration: '30s' }, { target: RATE, duration: DURATION }, { target: 0, duration: '15s' }],
    },
  },
  thresholds: {
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<400'],
    'payverse_transfer_latency': ['p(95)<1500'],
    'checks{check:transfer succeeded}': ['rate>0.99'],
  },
};

const auth = (u) => ({ headers: { Authorization: `Bearer ${u.token}`, 'Content-Type': 'application/json' } });
const pick = () => users[Math.floor(Math.random() * users.length)];

export function browse() {
  const u = pick();
  const paths = ['/api/v1/wallets/me', '/api/v1/transactions?size=20', '/api/v1/notifications/me?size=20', '/api/v1/rewards/me'];
  const res = http.get(BASE + paths[Math.floor(Math.random() * paths.length)], auth(u));
  check(res, { 'read ok': (r) => r.status === 200 });
}

export function transfer() {
  const payer = pick();
  let payee = pick();
  while (payee.vpa === payer.vpa) payee = pick();
  const amount = (1 + Math.floor(Math.random() * 400) / 100).toFixed(2); // INR 1.00–4.99
  const params = auth(payer);
  params.headers['Idempotency-Key'] = uuidv4();
  params.headers['X-Txn-Pin'] = seed.pin;
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({ payee: { type: 'VPA', value: payee.vpa }, amount }), params);
  transferLatency.add(res.timings.duration);
  const status = res.status === 201 || res.status === 202 ? res.json('status') : `HTTP_${res.status}`;
  transferOutcome.add(1, { status });
  check(res, { 'transfer succeeded': () => status === 'SUCCESS' || status === 'PENDING' });
}
