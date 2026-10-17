import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './client';
import { ApiError } from './errors';
import { session } from './session';
import { jsonResponse } from '../test/render';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  session.set({ accessToken: 'access-1', refreshToken: 'refresh-1' });
});
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  session.clear();
});

const headersOf = (call: number) => new Headers(fetchMock.mock.calls[call][1]!.headers as Record<string, string>);

describe('api client', () => {
  it('sends the bearer token, JSON body, Idempotency-Key and PIN', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }, 201));
    const result = await api<{ ok: boolean }>('/transfers', { method: 'POST', body: { amount: 10 }, idempotencyKey: 'key-1', pin: '4826', query: { a: 1, b: undefined } });
    expect(result).toEqual({ ok: true });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/transfers?a=1');
    const h = headersOf(0);
    expect(h.get('Authorization')).toBe('Bearer access-1');
    expect(h.get('Idempotency-Key')).toBe('key-1');
    expect(h.get('X-Txn-Pin')).toBe('4826');
    expect(fetchMock.mock.calls[0][1]!.body).toBe('{"amount":10}');
  });

  it('turns RFC 9457 problems into ApiError with code and extras', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 422, title: 'Limit exceeded', code: 'WALLET_LIMIT_EXCEEDED', detail: 'Daily limit', correlationId: 'c-1', limit: 'DAILY_DEBIT', available: '12.00' }, 422, 'application/problem+json'));
    const error = await api('/wallets/me/withdraw', { method: 'POST' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'WALLET_LIMIT_EXCEEDED', correlationId: 'c-1', extras: { limit: 'DAILY_DEBIT', available: '12.00' } });
  });

  it('refreshes once on 401 and retries with the same idempotency key', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ status: 401, code: 'UNAUTHORIZED' }, 401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' }))
      .mockResolvedValueOnce(jsonResponse({ status: 'SUCCESS' }, 201));
    const result = await api('/transfers', { method: 'POST', idempotencyKey: 'key-9', body: {} });
    expect(result).toEqual({ status: 'SUCCESS' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/token/refresh');
    expect(JSON.parse(fetchMock.mock.calls[1][1]!.body as string)).toMatchObject({ refreshToken: 'refresh-1' });
    expect(headersOf(2).get('Authorization')).toBe('Bearer access-2');
    expect(headersOf(2).get('Idempotency-Key')).toBe('key-9');
    expect(session.refreshToken).toBe('refresh-2');
  });

  it('shares one refresh between concurrent 401s (refresh tokens are single-use)', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/token/refresh')) {
        refreshCalls++;
        await new Promise((r) => setTimeout(r, 10));
        return jsonResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' });
      }
      const auth = new Headers(init!.headers as Record<string, string>).get('Authorization');
      return auth === 'Bearer access-2' ? jsonResponse({ url }) : jsonResponse({ status: 401 }, 401);
    });
    const results = await Promise.all([api('/wallets/me'), api('/users/me'), api('/rewards/me')]);
    expect(results).toHaveLength(3);
    expect(refreshCalls).toBe(1);
  });

  it('clears the session when the refresh token is rejected', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ status: 401 }, 401))
      .mockResolvedValueOnce(jsonResponse({ status: 401, code: 'AUTH_REFRESH_TOKEN_REUSED' }, 401));
    await expect(api('/wallets/me')).rejects.toMatchObject({ status: 401 });
    expect(session.accessToken).toBeNull();
    expect(session.refreshToken).toBeNull();
  });

  it('reports network failures as status 0', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(api('/wallets/me')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('returns the HTTP status when asked (202 PENDING vs 201)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'PENDING' }, 202));
    await expect(api('/transfers', { method: 'POST', withStatus: true })).resolves.toEqual({ status: 202, data: { status: 'PENDING' } });
  });
});
