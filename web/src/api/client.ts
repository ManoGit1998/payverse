import { ApiError } from './errors';
import { deviceId, session } from './session';
import type { TokenResponse } from './types';

export const API_BASE = '/api/v1';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  /** Money-moving calls: the same key on a retry returns the original result instead of paying twice. */
  idempotencyKey?: string;
  /** Transaction PIN (X-Txn-Pin) for transfers, withdrawals, bills, approvals. */
  pin?: string;
  /** Skip the Authorization header (OTP, refresh). */
  anonymous?: boolean;
  /** Return the raw Response (files: PNG QR codes, CSV/PDF statements). */
  raw?: boolean;
  /** Also return the HTTP status (e.g. 202 PENDING vs 201 SUCCESS). */
  withStatus?: boolean;
}

let refreshing: Promise<boolean> | null = null;

/** Rotates the refresh token once for all concurrent 401s (the server revokes the family on reuse). */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const refreshToken = session.refreshToken;
      if (!refreshToken) return false;
      try {
        const res = await fetch(`${API_BASE}/auth/token/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken, deviceId: deviceId() }),
        });
        if (!res.ok) {
          session.clear();
          return false;
        }
        const tokens = (await res.json()) as TokenResponse;
        session.set(tokens);
        return true;
      } catch {
        return false;
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

function url(path: string, query?: RequestOptions['query']): string {
  const params = new URLSearchParams();
  Object.entries(query ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  });
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

async function send(path: string, opts: RequestOptions): Promise<{ res: Response; token: string | null }> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json, */*' };
  let body: BodyInit | undefined;
  if (opts.body instanceof FormData) {
    body = opts.body;
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  const token = opts.anonymous ? null : session.accessToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;
  if (opts.pin) headers['X-Txn-Pin'] = opts.pin;
  try {
    return { res: await fetch(url(path, opts.query), { method: opts.method ?? 'GET', headers, body }), token };
  } catch {
    throw new ApiError(0, null);
  }
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  let { res, token } = await send(path, opts);
  if (res.status === 401 && !opts.anonymous) {
    // Another request may already have rotated the tokens while this one was in flight: then just retry.
    const renewed = (session.accessToken !== null && session.accessToken !== token) || (await refreshSession());
    if (renewed) {
      ({ res, token } = await send(path, opts)); // same idempotency key: a retry can never double-pay
    }
  }
  if (!res.ok) {
    let problem: Record<string, unknown> | null = null;
    try {
      problem = (await res.json()) as Record<string, unknown>;
    } catch {
      /* non-JSON error */
    }
    if (res.status === 401 && !opts.anonymous) session.clear();
    throw new ApiError(res.status, problem);
  }
  if (opts.raw) return res as unknown as T;
  const text = await res.text();
  const data = text ? (JSON.parse(text) as unknown) : undefined;
  return (opts.withStatus ? { status: res.status, data } : data) as T;
}

/** Downloads an authenticated file (statement, QR) and returns an object URL. */
export async function fileUrl(path: string, query?: RequestOptions['query']): Promise<string> {
  const res = await api<Response>(path, { query, raw: true });
  return URL.createObjectURL(await res.blob());
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
