import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage } from './errors';

describe('errorMessage', () => {
  it('uses friendly text and server extras', () => {
    expect(errorMessage(new ApiError(422, { code: 'AUTH_PIN_INCORRECT', remainingAttempts: 2 }))).toBe('Wrong PIN. 2 attempts left.');
    expect(errorMessage(new ApiError(422, { code: 'AUTH_PIN_INCORRECT', remainingAttempts: 1 }))).toBe('Wrong PIN. 1 attempt left.');
    expect(errorMessage(new ApiError(422, { code: 'WALLET_LIMIT_EXCEEDED', title: 'Limit exceeded', limit: 'DAILY_DEBIT', available: '50.00' })))
      .toBe('Limit exceeded You can still use ₹50.00 (daily debit).');
    expect(errorMessage(new ApiError(400, { code: 'SOMETHING_NEW', title: 'Bad', detail: 'Detail from server' }))).toBe('Detail from server');
    expect(errorMessage(new ApiError(503, null))).toMatch(/unavailable/);
    expect(errorMessage(new ApiError(500, { status: 500, title: 'Internal error' }))).toBe('Internal error');
    expect(errorMessage(new ApiError(0, null))).toMatch(/connection/);
    expect(errorMessage(new Error('boom'))).toBe('boom');
  });
});
