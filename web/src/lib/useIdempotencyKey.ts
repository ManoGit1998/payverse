import { useRef } from 'react';
import { newIdempotencyKey } from '../api/client';

/**
 * One Idempotency-Key per distinct request payload: retrying the same payment (e.g. after a timeout)
 * reuses the key, so the server replays the original result instead of paying twice; changing any
 * field gets a fresh key (reusing a key with a different body is rejected with IDEMPOTENCY_KEY_REUSED).
 * Call `reset()` after a definitive result so the next, identical payment is a new one.
 */
export function useIdempotencyKey() {
  const state = useRef<{ fingerprint: string; key: string } | null>(null);
  return {
    keyFor(payload: unknown): string {
      const fingerprint = JSON.stringify(payload);
      if (!state.current || state.current.fingerprint !== fingerprint) {
        state.current = { fingerprint, key: newIdempotencyKey() };
      }
      return state.current.key;
    },
    reset() {
      state.current = null;
    },
  };
}
