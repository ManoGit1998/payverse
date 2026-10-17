import { describe, expect, it } from 'vitest';
import { formatMoney, humanize, initials, isValidAmount, relativeTime } from './format';

describe('format', () => {
  it('formats INR with Indian digit grouping', () => {
    expect(formatMoney('1234567.5')).toBe('₹12,34,567.50');
    expect(formatMoney({ amount: '100.00', currency: 'INR' })).toBe('₹100.00');
    expect(formatMoney(null)).toBe('—');
  });

  it('humanizes codes', () => {
    expect(humanize('WALLET_INSUFFICIENT_BALANCE')).toBe('Insufficient balance');
    expect(humanize('TRANSFER_DEBIT')).toBe('Transfer debit');
    expect(humanize(null)).toBe('');
  });

  it('validates amounts', () => {
    expect(isValidAmount('100')).toBe(true);
    expect(isValidAmount('100.5')).toBe(true);
    expect(isValidAmount('100.555')).toBe(false);
    expect(isValidAmount('0.50')).toBe(false);
    expect(isValidAmount('abc')).toBe(false);
    expect(isValidAmount('5', 10)).toBe(false);
  });

  it('describes relative times and initials', () => {
    const now = new Date('2026-10-17T12:00:00Z');
    expect(relativeTime('2026-10-17T11:59:50Z', now)).toBe('just now');
    expect(relativeTime('2026-10-17T11:30:00Z', now)).toBe('30 min ago');
    expect(relativeTime('2026-10-16T11:00:00Z', now)).toBe('yesterday');
    expect(initials('Ravi Kumar (******3210)')).toBe('RK');
    expect(initials(null)).toBe('•');
  });
});
