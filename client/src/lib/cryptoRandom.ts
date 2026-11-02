/**
 * Import this before anything from @noble/*: noble captures globalThis.crypto when its module loads.
 * React Native has no Web Crypto, so getRandomValues is backed by expo-crypto's CSPRNG
 * (SecRandomCopyBytes on iOS, SecureRandom on Android). Browsers and Node already provide it.
 */
import * as ExpoCrypto from 'expo-crypto';

type RandomSource = { getRandomValues?: (array: Uint8Array) => Uint8Array };
const g = globalThis as { crypto?: RandomSource };

if (typeof g.crypto?.getRandomValues !== 'function') {
  const source: RandomSource = g.crypto ?? {};
  source.getRandomValues = (array) => ExpoCrypto.getRandomValues(array);
  g.crypto = source;
}

export {};
