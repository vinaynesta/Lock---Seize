import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { Vault } from '@/api/types';

import { deviceUnlocked, onKeysChanged, openVault, restoreDeviceKeys } from './sealedVault';

/** The account's Sealed Vault key bundle: undefined while loading, null if none was set up yet. */
export function useKeyBundle() {
  return useQuery({
    queryKey: ['keyBundle'],
    queryFn: async () => {
      try {
        return await api.keyBundle();
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

/** Re-renders when keys are unlocked or forgotten on this device. */
export function useKeysVersion(): number {
  const [n, setN] = useState(0);
  useEffect(() => onKeysChanged(() => setN((x) => x + 1)), []);
  return n;
}

export type SealedState =
  | { status: 'server' }
  | { status: 'loading' }
  | { status: 'no-bundle' }
  | { status: 'locked' }
  | { status: 'ready'; keyVersion: number }
  | { status: 'error'; error: unknown };

/**
 * For a vault screen: whether this device can read and write it. A server-side vault is always
 * 'server'. A sealed one is 'ready' once the device's keys are unlocked and the vault key is in hand.
 */
export function useSealedVault(vault: Vault | undefined): SealedState {
  const bundle = useKeyBundle();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const keysVersion = useKeysVersion();
  const queryClient = useQueryClient();
  const sealed = vault?.encryption === 'SEALED';

  const open = useQuery({
    queryKey: ['vaultKeys', vault?.id, vault?.keyVersion, keysVersion],
    enabled: sealed && !!bundle.data && !!me.data,
    queryFn: async () => {
      if (!deviceUnlocked() && !(await restoreDeviceKeys(bundle.data!.publicKey))) return { locked: true as const };
      const keyVersion = await openVault(vault!, me.data!.id);
      // Content fetched before the key arrived was left encrypted: refetch so it decrypts.
      void queryClient.invalidateQueries({ queryKey: ['timeline', vault!.id] });
      return { locked: false as const, keyVersion };
    },
    retry: false,
  });

  if (!vault) return { status: 'loading' };
  if (!sealed) return { status: 'server' };
  if (bundle.isPending || me.isPending) return { status: 'loading' };
  if (bundle.data === null) return { status: 'no-bundle' };
  if (open.error) return { status: 'error', error: open.error };
  if (!open.data) return { status: 'loading' };
  if (open.data.locked) return { status: 'locked' };
  return { status: 'ready', keyVersion: open.data.keyVersion };
}
