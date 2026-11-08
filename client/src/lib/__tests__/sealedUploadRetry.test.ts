/// <reference types="jest" />

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';

import { sendSealedUpload } from '../sealedFiles';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
  randomUUID: () => 'uuid',
}));
jest.mock('expo-image-manipulator', () => ({}));
jest.mock('expo-file-system', () => {
  const disk = new Map<string, Uint8Array>();
  class MockFile {
    uri: string;
    constructor(uri: string) {
      this.uri = uri;
    }
    get exists() {
      return disk.has(this.uri);
    }
    get size() {
      return disk.get(this.uri)?.length ?? 0;
    }
    delete() {
      disk.delete(this.uri);
    }
    async bytes() {
      return disk.get(this.uri)!;
    }
    open() {
      let offset = 0;
      const data = disk.get(this.uri)!;
      return {
        readBytes: async (n: number) => data.subarray(offset, (offset += n)),
        close: () => undefined,
      };
    }
    async upload() {
      return { status: 200 };
    }
  }
  return { File: MockFile, Directory: class {}, Paths: {}, FileMode: {}, __disk: disk };
});
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
jest.mock('@/api/lock', () => ({ api: { initiateUpload: jest.fn(), completeUpload: jest.fn() } }));

// eslint-disable-next-line @typescript-eslint/no-require-imports
const disk: Map<string, Uint8Array> = require('expo-file-system').__disk;

/** Roadmap 3.10: a queued sealed upload is safe to retry after a lost answer. */
describe('sealed upload retries', () => {
  test('an asset that already exists is completed, not failed, and the local ciphertext is deleted', async () => {
    disk.set('file:///pending/sealed-a1.lkm', new Uint8Array([1, 2, 3]));
    (api.initiateUpload as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'ASSET_EXISTS', 'exists'));
    (api.completeUpload as jest.Mock).mockResolvedValueOnce({ id: 'a1', uploadStatus: 'VERIFIED' });

    const asset = await sendSealedUpload('mem-1', 'VIDEO', 'video/mp4', { assetId: 'a1', fileUri: 'file:///pending/sealed-a1.lkm' });

    expect(asset.uploadStatus).toBe('VERIFIED');
    expect(api.completeUpload).toHaveBeenCalledWith('a1');
    expect(disk.has('file:///pending/sealed-a1.lkm')).toBe(false);
    // The checksum and id sent are of the ciphertext and the pre-chosen asset.
    expect((api.initiateUpload as jest.Mock).mock.calls[0]).toEqual(['mem-1', 'VIDEO', 'video/mp4', 3, expect.any(String), 'a1']);
  });

  test('other failures keep the ciphertext for the next try', async () => {
    disk.set('file:///pending/sealed-a2.lkm', new Uint8Array([4]));
    (api.initiateUpload as jest.Mock).mockRejectedValueOnce(new ApiError(503, undefined, 'down'));
    await expect(sendSealedUpload('mem-1', 'PHOTO', 'image/jpeg', { assetId: 'a2', fileUri: 'file:///pending/sealed-a2.lkm' })).rejects.toThrow('down');
    expect(disk.has('file:///pending/sealed-a2.lkm')).toBe(true);
  });
});
