import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

const KEY = 'lock.accessToken';

/**
 * Access token persistence. Keychain/Keystore on device. SecureStore is unavailable on web, so the
 * web build keeps the token in sessionStorage (cleared when the tab closes).
 */
export const tokenStore = {
  async get(): Promise<string | null> {
    if (Platform.OS === 'web') {
      return globalThis.sessionStorage?.getItem(KEY) ?? null;
    }
    return SecureStore.getItemAsync(KEY);
  },

  async set(token: string): Promise<void> {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.setItem(KEY, token);
      return;
    }
    await SecureStore.setItemAsync(KEY, token);
  },

  async clear(): Promise<void> {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.removeItem(KEY);
      return;
    }
    await SecureStore.deleteItemAsync(KEY);
  },
};
