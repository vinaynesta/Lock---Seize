import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { setAccessToken, setUnauthorizedHandler } from '@/api/client';
import { api } from '@/api/lock';
import type { TokenResponse } from '@/api/types';

import { tokenStore } from './tokenStore';

interface Session {
  /** True until the stored token has been read at launch. */
  loading: boolean;
  signedIn: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string, displayName: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

function isExpired(token: TokenResponse | string): boolean {
  try {
    const raw = typeof token === 'string' ? token : token.accessToken;
    const payload = JSON.parse(atob(raw.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return typeof payload.exp === 'number' && payload.exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [loading, setLoading] = useState(true);
  const [signedIn, setSignedIn] = useState(false);

  const signOut = useCallback(async () => {
    setAccessToken(null);
    await tokenStore.clear();
    queryClient.clear();
    setSignedIn(false);
  }, [queryClient]);

  const accept = useCallback(async (token: TokenResponse) => {
    setAccessToken(token.accessToken);
    await tokenStore.set(token.accessToken);
    setSignedIn(true);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => void signOut());
    (async () => {
      const stored = await tokenStore.get();
      if (stored && !isExpired(stored)) {
        setAccessToken(stored);
        setSignedIn(true);
      } else if (stored) {
        await tokenStore.clear();
      }
      setLoading(false);
    })();
    return () => setUnauthorizedHandler(null);
  }, [signOut]);

  const value = useMemo<Session>(
    () => ({
      loading,
      signedIn,
      signIn: async (email, password) => accept(await api.login(email, password)),
      signUp: async (email, password, displayName) => accept(await api.register(email, password, displayName)),
      signOut,
    }),
    [loading, signedIn, accept, signOut],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
