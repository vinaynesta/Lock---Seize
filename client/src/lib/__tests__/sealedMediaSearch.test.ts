/// <reference types="jest" />
import '../cryptoRandom'; // first, as in the app: noble reads globalThis.crypto when it loads
import { hmac } from '@noble/hashes/hmac';
import { sha256 } from '@noble/hashes/sha2';

import { parseQr } from '../deviceApproval';
import { generateKeyPair, generateVaultKey, toBase64, unwrapVaultKey, wrapVaultKey } from '../sealed';
import { decryptMedia, encryptMedia, encryptedSize, MEDIA_HEADER } from '../sealedMedia';
import { blind, indexTerms, memoryTokens, queryTokens, searchKey, words } from '../sealedSearch';

jest.mock('@/api/lock', () => ({ api: {} }));
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
}));

/** Roadmap 3.6d (media) and 3.6f (search): format, round trips, and the attacks that must fail. */
describe('sealed media', () => {
  const key = generateVaultKey();
  const bytes = (n: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31) % 251);

  test('round-trips across chunk boundaries and matches the size the server expects', () => {
    for (const n of [0, 1, 99, 100, 101, 350]) {
      const ct = encryptMedia(key, 'original', 'mem-1', 'asset-1', bytes(n), 100);
      expect(ct.length).toBe(encryptedSize(n, 100));
      expect(decryptMedia(key, 'original', 'mem-1', 'asset-1', ct, 100)).toEqual(bytes(n));
    }
    // Default 1 MiB chunks: header + plaintext + 16 per chunk (backend E2eFormats.mediaOverhead).
    expect(encryptedSize(3 * (1 << 20))).toBe(16 + 3 * (1 << 20) + 3 * 16);
  });

  test('truncation, reordering, tampering, wrong memory or purpose all fail', () => {
    const ct = encryptMedia(key, 'original', 'mem-1', 'asset-1', bytes(350), 100);
    const step = 116;
    expect(() => decryptMedia(key, 'original', 'mem-1', 'asset-1', ct.subarray(0, MEDIA_HEADER + 2 * step), 100)).toThrow();
    const swapped = new Uint8Array(ct);
    swapped.set(ct.subarray(MEDIA_HEADER + step, MEDIA_HEADER + 2 * step), MEDIA_HEADER);
    swapped.set(ct.subarray(MEDIA_HEADER, MEDIA_HEADER + step), MEDIA_HEADER + step);
    expect(() => decryptMedia(key, 'original', 'mem-1', 'asset-1', swapped, 100)).toThrow();
    const flipped = new Uint8Array(ct);
    flipped[MEDIA_HEADER + 5] ^= 1;
    expect(() => decryptMedia(key, 'original', 'mem-1', 'asset-1', flipped, 100)).toThrow();
    expect(() => decryptMedia(key, 'original', 'mem-2', 'asset-1', ct, 100)).toThrow();
    expect(() => decryptMedia(key, 'thumb', 'mem-1', 'asset-1', ct, 100)).toThrow();
    expect(() => decryptMedia(generateVaultKey(), 'original', 'mem-1', 'asset-1', ct, 100)).toThrow();
  });

  test('two encryptions of the same file look unrelated (fresh salt, so a fresh key)', () => {
    const a = encryptMedia(key, 'original', 'mem-1', 'asset-1', bytes(50));
    const b = encryptMedia(key, 'original', 'mem-1', 'asset-1', bytes(50));
    expect(toBase64(a)).not.toBe(toBase64(b));
  });
});

describe('sealed search', () => {
  test('normalises like the server tokenizer', () => {
    expect(words('Café Déjà-vu, 2026! café')).toEqual(['cafe', 'deja', 'vu', '2026']);
    expect(indexTerms('Graduation')).toEqual(['w:graduation', 'p:gra', 'p:grad', 'p:gradu', 'p:gradua', 'p:graduat', 'p:graduati', 'p:graduatio']);
    expect(words('a'.repeat(50))[0]).toHaveLength(40);
  });

  test('tokens are HMAC-SHA256 truncated to 16 bytes, under a key derived from the vault key', () => {
    const vaultKey = generateVaultKey();
    // Independent computation of the server's derivation (ContentCipher.searchKey + SearchTokenizer.blind).
    const enc = new TextEncoder();
    const expectedKey = hmac(sha256, vaultKey, enc.encode('lock:search-index:v1'));
    expect(toBase64(searchKey(vaultKey))).toBe(toBase64(expectedKey));
    const expected = toBase64(hmac(sha256, expectedKey, enc.encode('w:beach')).subarray(0, 16));
    expect(blind(searchKey(vaultKey), 'w:beach')).toBe(expected);
  });

  test('a prefix query finds the indexed word; other vaults and versions give other tokens', () => {
    const v1 = generateVaultKey();
    const v2 = generateVaultKey();
    const stored = new Set(memoryTokens(v1, 'Our beach trip', null, 'happy'));
    const [beach] = queryTokens([v1, v2], 'BEA');
    expect(beach.some((t) => stored.has(t))).toBe(true);
    const [other] = queryTokens([v2], 'beach');
    expect(other.some((t) => stored.has(t))).toBe(false);
    expect(queryTokens([v1], 'beach trip')).toHaveLength(2);
  });
});

describe('device approval', () => {
  // The flow without the network: what the approving device seals, the new device can open, and only it.
  test('the sealed secret opens only with the one-time key and the same request id', () => {
    const account = generateKeyPair();
    const oneTime = generateKeyPair();
    const sealed = wrapVaultKey(account.secretKey, oneTime.publicKey, 'device-approval', 1, 'user:req-1');
    expect(toBase64(unwrapVaultKey(sealed, oneTime, 'device-approval', 1, 'user:req-1'))).toBe(toBase64(account.secretKey));
    expect(() => unwrapVaultKey(sealed, generateKeyPair(), 'device-approval', 1, 'user:req-1')).toThrow();
    expect(() => unwrapVaultKey(sealed, oneTime, 'device-approval', 1, 'user:req-2')).toThrow();
    // A vault-key wrap can't be passed off as an approval (different purpose binding).
    expect(() => unwrapVaultKey(sealed, oneTime, 'vault-1', 1, 'user:req-1')).toThrow();

    expect(parseQr('lock-approve:v1:abc:' + '0123456789abcdef'.repeat(2))).toEqual({
      id: 'abc',
      fingerprint: '0123 4567 89ab cdef 0123 4567 89ab cdef',
    });
    expect(parseQr('https://evil.example')).toBeNull();
    expect(parseQr('lock-approve:v1:abc:short')).toBeNull();
  });
});
