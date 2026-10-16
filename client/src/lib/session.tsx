import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { getRefreshToken, setTokens, setTokensChangedHandler, setUnauthorizedHandler } from '@/api/client';
import { api } from '@/api/lock';
import type { TokenResponse } from '@/api/types';

import { tokenStore } from './tokenStore';

interface Session {
  /** True until the stored tokens have been read at launch. */
  loading: boolean;
  signedIn: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string, displayName: string) => Promise<void>;
  /** Changes the password; other devices are signed out, this one stays signed in. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [loading, setLoading] = useState(true);
  const [signedIn, setSignedIn] = useState(false);

  const clearLocal = useCallback(async () => {
    setTokens(null);
    await tokenStore.clear();
    queryClient.clear();
    setSignedIn(false);
  }, [queryClient]);

  const signOut = useCallback(async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      // Best effort: revoke server-side, but never keep the user signed in because the call failed.
      await api.logout(refreshToken).catch(() => undefined);
    }
    await clearLocal();
  }, [clearLocal]);

  const accept = useCallback(async (token: TokenResponse) => {
    const tokens = { accessToken: token.accessToken, refreshToken: token.refreshToken };
    setTokens(tokens);
    await tokenStore.set(tokens);
    setSignedIn(true);
  }, []);

  useEffect(() => {
    // An expired refresh token or a revoked session: nothing to revoke, just forget it locally.
    setUnauthorizedHandler(() => void clearLocal());
    setTokensChangedHandler((tokens) => void tokenStore.set(tokens));
    (async () => {
      // An expired access token is fine here: the first request renews it with the refresh token.
      const stored = await tokenStore.get();
      if (stored) {
        setTokens(stored);
        setSignedIn(true);
      }
      setLoading(false);
    })();
    return () => {
      setUnauthorizedHandler(null);
      setTokensChangedHandler(null);
    };
  }, [clearLocal]);

  const value = useMemo<Session>(
    () => ({
      loading,
      signedIn,
      signIn: async (email, password) => accept(await api.login(email, password)),
      signUp: async (email, password, displayName) => accept(await api.register(email, password, displayName)),
      changePassword: async (currentPassword, newPassword) =>
        accept(await api.changePassword(currentPassword, newPassword)),
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
