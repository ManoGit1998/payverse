/**
 * Token storage. The access token (15 min) lives only in memory; the refresh token is kept in
 * localStorage so a reload keeps you signed in. That trade-off is fine for this demo SPA — a
 * production deployment would keep the refresh token in an HttpOnly cookie behind a BFF.
 */
const REFRESH_KEY = 'payverse.refreshToken';
const DEVICE_KEY = 'payverse.deviceId';

let accessToken: string | null = null;
const listeners = new Set<() => void>();

export const session = {
  get accessToken() {
    return accessToken;
  },
  get refreshToken(): string | null {
    try {
      return localStorage.getItem(REFRESH_KEY);
    } catch {
      return null;
    }
  },
  set(tokens: { accessToken: string; refreshToken: string }) {
    accessToken = tokens.accessToken;
    try {
      localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
    } catch {
      /* storage unavailable: session lasts until reload */
    }
    listeners.forEach((l) => l());
  },
  clear() {
    accessToken = null;
    try {
      localStorage.removeItem(REFRESH_KEY);
    } catch {
      /* ignore */
    }
    listeners.forEach((l) => l());
  },
  onChange(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

/** One stable device id per browser: auth binds sessions and the fraud new-device rule to it. */
export function deviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_KEY);
    if (!id) {
      id = `web-${crypto.randomUUID()}`;
      localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch {
    return 'web-ephemeral-device';
  }
}

export function deviceName(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Mac OS X/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Linux/.test(ua) ? 'Linux' : '';
  return `${browser}${os ? ' on ' + os : ''} (web)`;
}
