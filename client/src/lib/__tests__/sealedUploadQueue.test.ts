/// <reference types="jest" />

import AsyncStorage from '@react-native-async-storage/async-storage';

import { discard, drain, queueSealedUpload, subscribe, type QueueItem } from '../offlineQueue';
import { discardPreparedUpload, prepareSealedUpload, sendSealedUpload } from '../sealedFiles';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('expo-crypto', () => ({ randomUUID: () => 'q-1', getRandomValues: (a: Uint8Array) => a }));
jest.mock('expo-network', () => ({ getNetworkStateAsync: jest.fn(async () => ({ isConnected: false })) }));
jest.mock('expo-file-system', () => ({
  Directory: class {
    exists = true;
    create() {}
  },
  File: class {},
  Paths: { document: 'file:///docs' },
}));
jest.mock('@react-native-async-storage/async-storage', () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('@/api/client', () => ({ ApiError: class extends Error {} }));
jest.mock('@/api/lock', () => ({ api: {} }));
jest.mock('@/lib/sealedVault', () => ({ reencryptSealedDraft: jest.fn(), sendSealedDraft: jest.fn() }));
jest.mock('@/lib/sealedFiles', () => ({
  prepareSealedUpload: jest.fn(async () => ({ assetId: 'asset-1', fileUri: 'file:///docs/pending-uploads/sealed-asset-1.lkm' })),
  sendSealedUpload: jest.fn(async () => ({ id: 'asset-1', uploadStatus: 'VERIFIED' })),
  discardPreparedUpload: jest.fn(),
}));

/** Roadmap 3.10: sealed media is encrypted before it is queued, then sent when online. */
describe('queued sealed uploads', () => {
  test('encrypts into the persistent queue folder, stores only the prepared ciphertext reference, sends when online', async () => {
    await queueSealedUpload('vault-1', 2, 'mem-1', 'file:///picker/IMG_1.jpg', 'PHOTO', 'image/jpeg');
    expect(prepareSealedUpload).toHaveBeenCalledWith('vault-1', 2, 'mem-1', 'file:///picker/IMG_1.jpg', 'PHOTO', expect.anything());

    const stored = (await AsyncStorage.getItem('lock.offline-queue.v1')) ?? '';
    expect(stored).toContain('sealed-asset-1.lkm');
    expect(stored).not.toContain('IMG_1.jpg'); // the plaintext source isn't kept
    expect(sendSealedUpload).not.toHaveBeenCalled(); // offline

    // eslint-disable-next-line @typescript-eslint/no-require-imports
    (require('expo-network').getNetworkStateAsync as jest.Mock).mockResolvedValue({ isConnected: true, isInternetReachable: true });
    await drain();
    expect(sendSealedUpload).toHaveBeenCalledWith('mem-1', 'PHOTO', 'image/jpeg', expect.objectContaining({ assetId: 'asset-1' }));
    const left: QueueItem[] = await new Promise((resolve) => subscribe(resolve));
    expect(left).toHaveLength(0);
  });

  test('giving up on a queued sealed upload deletes its ciphertext', async () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    (require('expo-network').getNetworkStateAsync as jest.Mock).mockResolvedValue({ isConnected: false });
    await queueSealedUpload('vault-1', 2, 'mem-1', 'file:///picker/IMG_2.jpg', 'PHOTO', 'image/jpeg');
    await discard('q-1');
    expect(discardPreparedUpload).toHaveBeenCalledWith(expect.objectContaining({ assetId: 'asset-1' }));
  });
});
