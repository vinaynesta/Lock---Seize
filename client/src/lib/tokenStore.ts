import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import type { Tokens } from '@/api/client';

const KEY = 'lock.session';

/**
 * Session token persistence (access + refresh token). Keychain/Keystore on device. SecureStore is
 * unavailable on web, so the web build keeps them in sessionStorage (cleared when the tab closes).
 */
export const tokenStore = {
  async get(): Promise<Tokens | null> {
    const raw =
      Platform.OS === 'web' ? (globalThis.sessionStorage?.getItem(KEY) ?? null) : await SecureStore.getItemAsync(KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as Partial<Tokens>;
      return parsed.accessToken && parsed.refreshToken
        ? { accessToken: parsed.accessToken, refreshToken: parsed.refreshToken }
        : null;
    } catch {
      return null;
    }
  },

  async set(tokens: Tokens): Promise<void> {
    const raw = JSON.stringify(tokens);
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.setItem(KEY, raw);
      return;
    }
    await SecureStore.setItemAsync(KEY, raw);
  },

  async clear(): Promise<void> {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.removeItem(KEY);
      return;
    }
    await SecureStore.deleteItemAsync(KEY);
  },
};
