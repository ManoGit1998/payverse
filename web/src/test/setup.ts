import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Node ≥ 22 ships its own global `localStorage` (inactive without --localstorage-file), which shadows jsdom's.
// Give the tests a working in-memory Storage when that happens.
function usable(storage: unknown): storage is Storage {
  try {
    return typeof (storage as Storage)?.setItem === 'function' && typeof (storage as Storage).clear === 'function';
  } catch {
    return false;
  }
}
if (!usable(globalThis.localStorage)) {
  const store = new Map<string, string>();
  const memory: Storage = {
    getItem: (k) => store.get(k) ?? null,
    setItem: (k, v) => void store.set(k, String(v)),
    removeItem: (k) => void store.delete(k),
    clear: () => store.clear(),
    key: (i) => [...store.keys()][i] ?? null,
    get length() {
      return store.size;
    },
  };
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: memory });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});
