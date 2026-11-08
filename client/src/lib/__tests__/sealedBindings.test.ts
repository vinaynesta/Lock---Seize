/// <reference types="jest" />

import { gcm } from '@noble/ciphers/aes';
import { hkdf } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';

import { encryptField, generateVaultKey, SealedError } from '../sealed';
import { decryptMemory, decryptReflection, formatCoordinates, reflectionAad, vaultKeys } from '../sealedDecrypt';
import { decryptMedia, encryptMedia, MEDIA_HEADER } from '../sealedMedia';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
}));

const bytes = (n: number) => Uint8Array.from({ length: n }, (_, i) => i % 251);

/** Security review F5 and F6: ciphertexts are bound to what they belong to. */
describe('content bindings', () => {
  test('media is bound to its asset; files from before the binding still open (F6)', () => {
    const key = generateVaultKey();
    const ct = encryptMedia(key, 'original', 'mem-1', 'asset-1', bytes(300), 100);
    expect(ct[4]).toBe(2);
    expect(decryptMedia(key, 'original', 'mem-1', 'asset-1', ct, 100)).toEqual(bytes(300));
    expect(() => decryptMedia(key, 'original', 'mem-1', 'asset-2', ct, 100)).toThrow(SealedError); // swapped asset

    // A version-1 file, built the old way: same chunking, info without the asset id.
    const header = new Uint8Array(MEDIA_HEADER);
    header.set([0x4c, 0x4b, 0x4d, 0x31, 1], 0);
    header.set(bytes(11), 5);
    const fileKey = hkdf(sha256, key, header.subarray(5), new TextEncoder().encode('lock:media:v1:original:mem-1'), 32);
    const nonce = new Uint8Array(12);
    nonce[11] = 1;
    const v1 = new Uint8Array([...header, ...gcm(fileKey, nonce, header).encrypt(bytes(40))]);
    expect(decryptMedia(key, 'original', 'mem-1', 'any-asset', v1, 100)).toEqual(bytes(40));
  });

  test('reflections are bound to their memory and author; older ones still open (F5)', () => {
    const key = generateVaultKey();
    vaultKeys.set('vault-1:1', key);
    const aad = reflectionAad('mem-1', 'ref-1', 'ana');
    const sealedBody = encryptField(key, aad.recordId, aad.field, 'Still makes me smile');
    const r = { id: 'ref-1', authorId: 'ana', body: null, createdAt: '2026-11-08T00:00:00Z', keyVersion: 1, sealedBody };

    expect(decryptReflection('vault-1', 'mem-1', r).body).toBe('Still makes me smile');
    expect(() => decryptReflection('vault-1', 'mem-2', r)).toThrow(SealedError); // moved to another memory
    expect(() => decryptReflection('vault-1', 'mem-1', { ...r, authorId: 'bo' })).toThrow(SealedError); // re-attributed

    const legacy = { ...r, sealedBody: encryptField(key, 'ref-1', 'reflection', 'Written before v2') };
    expect(decryptReflection('vault-1', 'mem-1', legacy).body).toBe('Written before v2');
  });

  test('a sealed memory map position is encrypted like any field and bound to its memory (5.1)', () => {
    const key = generateVaultKey();
    vaultKeys.set('vault-2:1', key);
    const coordinates = encryptField(key, 'mem-9', 'coordinates', formatCoordinates({ latitude: 38.713889, longitude: -9.133333 })!);
    const m = {
      id: 'mem-9', vaultId: 'vault-2', title: null, thought: null, feeling: null, location: null,
      encrypted: { keyVersion: 1, title: encryptField(key, 'mem-9', 'title', 'Lisbon'), thought: null, feeling: null, location: null, coordinates },
    } as unknown as Parameters<typeof decryptMemory>[0];
    expect(decryptMemory(m).coordinates).toEqual({ latitude: 38.713889, longitude: -9.133333 });
    expect(() => decryptMemory({ ...m, id: 'mem-10' })).toThrow(SealedError); // moved to another memory
  });
});
