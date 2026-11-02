/// <reference types="jest" />

import {
  SealedError,
  createBundle,
  decryptField,
  encryptField,
  fingerprint,
  fromBase64,
  generateKeyPair,
  generateRecoveryPhrase,
  generateVaultKey,
  isValidRecoveryPhrase,
  rewrapPassphrase,
  toBase64,
  unlockWithPassphrase,
  unlockWithRecoveryPhrase,
  unwrapVaultKey,
  wrapVaultKey,
} from '../sealed';

// The jest-expo environment hides Node's Web Crypto; the app gets randomness from expo-crypto instead.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
}));

/** Roadmap 3.6 pieces 1-3: real round trips, and the failures that must not silently succeed. */
describe('sealed vault crypto', () => {
  jest.setTimeout(60_000); // Argon2id in plain JS

  test('recovery phrases are 24 valid BIP-39 words', () => {
    const phrase = generateRecoveryPhrase();
    expect(phrase.split(' ')).toHaveLength(24);
    expect(isValidRecoveryPhrase(phrase)).toBe(true);
    expect(isValidRecoveryPhrase(`  ${phrase.toUpperCase()}  `)).toBe(true); // forgiving about case/spacing
    const words = phrase.split(' ');
    [words[0], words[1]] = [words[1], words[0]];
    expect(isValidRecoveryPhrase(words.join(' '))).toBe(false); // the checksum catches swapped words
  });

  test('the bundle has the server-checked shape and unlocks with the passphrase or the phrase', async () => {
    const phrase = generateRecoveryPhrase();
    const { bundle, pair } = await createBundle('correct horse battery', phrase);
    expect(fromBase64(bundle.publicKey)).toHaveLength(32);
    expect(fromBase64(bundle.wrappedByPassphrase)).toHaveLength(61);
    expect(fromBase64(bundle.wrappedByRecovery)).toHaveLength(61);
    expect(bundle.passphraseKdf).toMatchObject({ alg: 'argon2id', m: 19456, t: 2, p: 1 });
    expect(JSON.stringify(bundle)).not.toContain(toBase64(pair.secretKey));

    const viaPassphrase = await unlockWithPassphrase(bundle, 'correct horse battery');
    expect(toBase64(viaPassphrase.secretKey)).toBe(toBase64(pair.secretKey));
    const viaPhrase = unlockWithRecoveryPhrase(bundle, phrase);
    expect(toBase64(viaPhrase.secretKey)).toBe(toBase64(pair.secretKey));

    await expect(unlockWithPassphrase(bundle, 'wrong horse')).rejects.toThrow(SealedError);
    expect(() => unlockWithRecoveryPhrase(bundle, generateRecoveryPhrase())).toThrow(SealedError);
  });

  test('a new passphrase opens the same key, and the old one no longer does', async () => {
    const phrase = generateRecoveryPhrase();
    const { bundle, pair } = await createBundle('first passphrase', phrase);
    const rewrapped = { ...bundle, ...(await rewrapPassphrase(pair.secretKey, 'second passphrase')) };
    expect(toBase64((await unlockWithPassphrase(rewrapped, 'second passphrase')).secretKey)).toBe(toBase64(pair.secretKey));
    await expect(unlockWithPassphrase(rewrapped, 'first passphrase')).rejects.toThrow(SealedError);
  });

  test('vault keys open only for their recipient, vault and version', () => {
    const ana = generateKeyPair();
    const bo = generateKeyPair();
    const vaultKey = generateVaultKey();
    const wrapped = wrapVaultKey(vaultKey, ana.publicKey, 'vault-1', 3, 'ana');
    expect(wrapped).toHaveLength(93);
    expect(toBase64(unwrapVaultKey(wrapped, ana, 'vault-1', 3, 'ana'))).toBe(toBase64(vaultKey));

    expect(() => unwrapVaultKey(wrapped, bo, 'vault-1', 3, 'ana')).toThrow(SealedError); // not Bo's
    expect(() => unwrapVaultKey(wrapped, ana, 'vault-2', 3, 'ana')).toThrow(SealedError); // replayed elsewhere
    expect(() => unwrapVaultKey(wrapped, ana, 'vault-1', 2, 'ana')).toThrow(SealedError); // another version
    // Two wraps of the same key look unrelated (fresh ephemeral key and nonce each time).
    expect(toBase64(wrapVaultKey(vaultKey, ana.publicKey, 'vault-1', 3, 'ana'))).not.toBe(toBase64(wrapped));
  });

  test('content fields round-trip, are bound to their record and field, and detect tampering', () => {
    const key = generateVaultKey();
    const ct = encryptField(key, 'memory-1', 'title', 'Our first trip 🌊');
    expect(fromBase64(ct)).toHaveLength(12 + new TextEncoder().encode('Our first trip 🌊').length + 16);
    expect(decryptField(key, 'memory-1', 'title', ct)).toBe('Our first trip 🌊');

    expect(() => decryptField(key, 'memory-2', 'title', ct)).toThrow(SealedError); // copied to another row
    expect(() => decryptField(key, 'memory-1', 'thought', ct)).toThrow(SealedError); // or another column
    expect(() => decryptField(generateVaultKey(), 'memory-1', 'title', ct)).toThrow(SealedError);
    const tampered = fromBase64(ct);
    tampered[20] ^= 1;
    expect(() => decryptField(key, 'memory-1', 'title', toBase64(tampered))).toThrow(SealedError);
  });

  test('fingerprints match the server format', () => {
    const fp = fingerprint(new Uint8Array(32));
    // SHA-256 of 32 zero bytes starts 66687aadf862bd776c8fc18b8e9f8e20
    expect(fp).toBe('6668 7aad f862 bd77 6c8f c18b 8e9f 8e20');
  });
});
