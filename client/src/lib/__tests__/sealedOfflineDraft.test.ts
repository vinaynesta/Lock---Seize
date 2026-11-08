/// <reference types="jest" />

import AsyncStorage from '@react-native-async-storage/async-storage';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';

import { drain, queueSealedDraft } from '../offlineQueue';
import { decryptField, generateVaultKey } from '../sealed';
import { vaultKeys } from '../sealedDecrypt';
import { buildSealedDraft, reencryptSealedDraft } from '../sealedVault';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  randomUUID: () => require('node:crypto').randomUUID(),
}));
jest.mock('expo-network', () => ({ getNetworkStateAsync: async () => ({ isConnected: true, isInternetReachable: true }) }));
jest.mock('expo-secure-store', () => ({ WHEN_UNLOCKED_THIS_DEVICE_ONLY: 0 }));
jest.mock('expo-file-system', () => ({ File: class {}, Directory: class {}, Paths: {}, FileMode: {} }));
jest.mock('expo-image-manipulator', () => ({}));
jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('@/api/client', () => {
  class MockApiError extends Error {
    status: number;
    code: string | undefined;
    constructor(status: number, code: string | undefined, message: string) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
  return { ApiError: MockApiError };
});
jest.mock('@/api/lock', () => ({ api: { createSealedMemory: jest.fn() } }));

const vault = { id: 'vault-9' } as Parameters<typeof buildSealedDraft>[0];
const input = { title: 'The night the power went out', thought: 'Candles everywhere', occurredAt: '2026-11-01T20:00:00Z' };

/** Roadmap 3.6 known limit closed: sealed drafts can be captured offline, as ciphertext only. */
describe('offline sealed drafts', () => {
  beforeEach(() => {
    vaultKeys.clear();
    vaultKeys.set('vault-9:1', generateVaultKey());
  });

  test('the queued body holds no plaintext, and syncs with the same id and Idempotency-Key', async () => {
    const body = buildSealedDraft(vault, 1, input);
    (api.createSealedMemory as jest.Mock).mockResolvedValueOnce({ id: body.id, vaultId: 'vault-9' });
    await queueSealedDraft('vault-9', body, 'idem-1');
    await drain();

    const stored = JSON.stringify(await AsyncStorage.getItem('lock.offline-queue.v1'));
    expect(stored).not.toContain('power went out');
    expect(stored).not.toContain('Candles');
    expect(api.createSealedMemory).toHaveBeenCalledWith('vault-9', body, 'idem-1');
  });

  test('a key version that moved on while offline is re-encrypted under the newest key', async () => {
    const body = buildSealedDraft(vault, 1, input);
    expect(reencryptSealedDraft('vault-9', body)).toBeNull(); // no newer key on this device yet

    const v2 = generateVaultKey();
    vaultKeys.set('vault-9:2', v2);
    (api.createSealedMemory as jest.Mock)
      .mockReset()
      .mockRejectedValueOnce(new ApiError(409, 'STALE_KEY_VERSION', 'stale'))
      .mockResolvedValueOnce({ id: body.id, vaultId: 'vault-9' });
    await queueSealedDraft('vault-9', body, 'idem-2');
    await drain();

    const resent = (api.createSealedMemory as jest.Mock).mock.calls[1][1];
    expect(resent.id).toBe(body.id);
    expect(resent.sealed.keyVersion).toBe(2);
    expect(decryptField(v2, body.id, 'title', resent.sealed.title)).toBe(input.title);
  });
});
