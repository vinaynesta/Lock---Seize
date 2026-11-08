/// <reference types="jest" />

import { acceptLegacyWrap, acceptNewKey, KeyChangedError, markVerified, observeMemberKeys, senderKey } from '../keyPins';
import { fromBase64, generateKeyPair, toBase64 } from '../sealed';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('@react-native-async-storage/async-storage', () => {
  const store = new Map<string, string>();
  return {
    __esModule: true,
    default: {
      getItem: async (k: string) => store.get(k) ?? null,
      setItem: async (k: string, v: string) => void store.set(k, v),
    },
  };
});
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
}));

const key = () => toBase64(generateKeyPair().publicKey);

/** Security review F1/F2: trust on first use, then a changed key is refused until the user accepts it. */
describe('pinned member keys', () => {
  test('first sight pins, a later different key is reported as changed', async () => {
    const [a, a2, b] = [key(), key(), key()];
    expect(await observeMemberKeys('me-1', [{ userId: 'a', publicKey: a }, { userId: 'b', publicKey: b }])).toEqual(new Set());
    expect(await observeMemberKeys('me-1', [{ userId: 'a', publicKey: a2 }, { userId: 'b', publicKey: b }])).toEqual(new Set(['a']));
    // A wrap verified against the server's new key is refused; the pinned key is used otherwise.
    await expect(senderKey('me-1', 'a', a2)).rejects.toBeInstanceOf(KeyChangedError);
    expect(toBase64((await senderKey('me-1', 'b', b))!)).toBe(b);
    // Verifying is not possible while a change is pending; accepting the new key re-pins it.
    await markVerified('me-1', 'a');
    await acceptNewKey('me-1', 'a');
    expect(await observeMemberKeys('me-1', [{ userId: 'a', publicKey: a2 }])).toEqual(new Set());
    expect(toBase64((await senderKey('me-1', 'a', a2))!)).toBe(a2);
  });

  test('an unpinned sender is pinned from the key stored with the wrap; none means unverifiable', async () => {
    const c = key();
    expect(fromBase64(toBase64((await senderKey('me-2', 'c', c))!))).toEqual(fromBase64(c));
    await expect(senderKey('me-2', 'c', key())).rejects.toBeInstanceOf(KeyChangedError);
    expect(await senderKey('me-2', 'nobody', null)).toBeNull();
  });

  test('legacy wraps are accepted only up to the version first seen on this device', async () => {
    expect(await acceptLegacyWrap('me-3', 'v', 2, 2)).toBe(true);
    expect(await acceptLegacyWrap('me-3', 'v', 1, 5)).toBe(true);
    expect(await acceptLegacyWrap('me-3', 'v', 3, 5)).toBe(false); // planted after the upgrade
  });

  test('pins are per account', async () => {
    const d = key();
    await observeMemberKeys('me-4', [{ userId: 'd', publicKey: d }]);
    expect(await observeMemberKeys('me-5', [{ userId: 'd', publicKey: key() }])).toEqual(new Set());
  });
});
