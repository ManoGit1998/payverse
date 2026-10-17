/**
 * RFC 9457 problem details returned by every PayVerse service, e.g.
 * { status: 422, code: "WALLET_LIMIT_EXCEEDED", title, detail, correlationId, limit: "DAILY_DEBIT", available: "120.00" }.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string | undefined;
  readonly correlationId: string | undefined;
  readonly extras: Record<string, unknown>;

  constructor(status: number, body: Record<string, unknown> | null) {
    const code = typeof body?.code === 'string' ? body.code : status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`;
    // 502/503/504, or a 5xx without a problem body (e.g. the dev proxy cannot reach the gateway) = unavailable
    const unavailable = status === 502 || status === 503 || status === 504 || (status >= 500 && !body);
    const title = typeof body?.title === 'string' ? body.title : unavailable ? 'PayVerse is unavailable right now — try again shortly' : defaultTitle(status);
    const detail = typeof body?.detail === 'string' ? body.detail : undefined;
    super(detail ?? title);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.title = title;
    this.detail = detail;
    this.correlationId = typeof body?.correlationId === 'string' ? body.correlationId : undefined;
    const { type: _t, title: _ti, status: _s, detail: _d, instance: _i, code: _c, correlationId: _ci, timestamp: _ts, ...extras } = body ?? {};
    this.extras = extras;
  }
}

function defaultTitle(status: number): string {
  if (status === 0) return 'Cannot reach PayVerse — check your connection';
  if (status === 401) return 'Your session has expired';
  if (status === 403) return 'You are not allowed to do that';
  if (status === 429) return 'Too many attempts — wait a minute and try again';
  if (status >= 500) return 'Something went wrong on our side';
  return 'Request failed';
}

/** Friendly sentences for the codes users run into most; everything else shows the server's detail/title. */
const MESSAGES: Record<string, string> = {
  AUTH_OTP_INVALID: 'That code is not right. Check the latest OTP and try again.',
  AUTH_OTP_EXPIRED: 'That code has expired. Request a new one.',
  AUTH_PIN_INCORRECT: 'Wrong PIN.',
  AUTH_PIN_LOCKED: 'Your PIN is locked after too many wrong attempts. Reset it with an OTP.',
  AUTH_PIN_WEAK: 'Pick a less guessable PIN (not 1234, 0000, 1111…).',
  WALLET_INSUFFICIENT_BALANCE: 'Not enough balance in your wallet.',
  WALLET_KYC_REQUIRED: 'Complete minimum KYC to move money.',
  WALLET_FROZEN: 'This wallet is frozen. Contact support.',
  WALLET_PAYMENT_DECLINED: 'The payment was declined by the bank.',
  TXN_RISK_BLOCKED: 'This payment was blocked for your safety. Contact support if it was you.',
  TXN_SELF_TRANSFER: 'You cannot send money to yourself.',
  PAYMENT_VPA_EXTERNAL_UNSUPPORTED: 'Only @payverse UPI IDs can be paid in this simulation.',
  IDEMPOTENCY_KEY_REUSED: 'This request was already sent with different details.',
  RATE_LIMITED: 'Too many attempts — wait a minute and try again.',
};

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const base = MESSAGES[error.code] ?? error.detail ?? error.title;
    if (error.code === 'AUTH_PIN_INCORRECT' && typeof error.extras.remainingAttempts === 'number') {
      return `${base} ${error.extras.remainingAttempts} attempt${error.extras.remainingAttempts === 1 ? '' : 's'} left.`;
    }
    if (error.code === 'WALLET_LIMIT_EXCEEDED' && typeof error.extras.available === 'string') {
      return `${base} You can still use ₹${error.extras.available} (${String(error.extras.limit ?? 'limit').toLowerCase().replace(/_/g, ' ')}).`;
    }
    return base;
  }
  return error instanceof Error ? error.message : 'Something went wrong';
}
