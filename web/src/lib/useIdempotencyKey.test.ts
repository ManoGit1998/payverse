import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useIdempotencyKey } from './useIdempotencyKey';

describe('useIdempotencyKey', () => {
  it('reuses the key for the same payload and rotates it when the payload changes or after reset', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current.keyFor({ payee: 'a@payverse', amount: '10' });
    expect(result.current.keyFor({ payee: 'a@payverse', amount: '10' })).toBe(first);
    const changed = result.current.keyFor({ payee: 'a@payverse', amount: '11' });
    expect(changed).not.toBe(first);
    result.current.reset();
    expect(result.current.keyFor({ payee: 'a@payverse', amount: '11' })).not.toBe(changed);
  });
});
