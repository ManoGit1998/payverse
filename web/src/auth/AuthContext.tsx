import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { refreshSession } from '../api/client';
import { authApi } from '../api/endpoints';
import { session } from '../api/session';
import type { MeResponse, TokenResponse } from '../api/types';

type Status = 'loading' | 'anonymous' | 'authenticated';

interface AuthState {
  status: Status;
  me: MeResponse | null;
  isStaff: boolean;
  isAdmin: boolean;
  signIn: (tokens: TokenResponse) => Promise<MeResponse>;
  signOut: () => Promise<void>;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading');
  const [me, setMe] = useState<MeResponse | null>(null);
  const queryClient = useQueryClient();

  const loadMe = useCallback(async () => {
    const who = await authApi.me();
    setMe(who);
    setStatus('authenticated');
    return who;
  }, []);

  // On start: a stored refresh token silently restores the session.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (session.refreshToken && (await refreshSession())) {
        try {
          if (!cancelled) await loadMe();
          return;
        } catch {
          /* fall through to anonymous */
        }
      }
      if (!cancelled) setStatus('anonymous');
    })();
    return () => {
      cancelled = true;
    };
  }, [loadMe]);

  // The API client clears the session when a refresh fails: follow it.
  useEffect(
    () =>
      session.onChange(() => {
        if (!session.accessToken && !session.refreshToken) {
          setMe(null);
          setStatus('anonymous');
          queryClient.clear();
        }
      }),
    [queryClient],
  );

  const signIn = useCallback(
    async (tokens: TokenResponse) => {
      session.set(tokens);
      return loadMe();
    },
    [loadMe],
  );

  const signOut = useCallback(async () => {
    const refreshToken = session.refreshToken;
    try {
      if (refreshToken) await authApi.logout(refreshToken);
    } catch {
      /* the session ends locally either way */
    }
    session.clear();
  }, []);

  const value = useMemo<AuthState>(() => {
    const roles = me?.roles ?? [];
    return {
      status,
      me,
      isStaff: roles.includes('ADMIN') || roles.includes('SUPPORT'),
      isAdmin: roles.includes('ADMIN'),
      signIn,
      signOut,
      refreshMe: async () => {
        await loadMe();
      },
    };
  }, [status, me, signIn, signOut, loadMe]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
