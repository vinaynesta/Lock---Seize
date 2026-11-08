/// <reference types="jest" />

/**
 * Sealed Vault protocol test vectors (docs/sealed-vault-test-vectors.json, spec: docs/sealed-vault-protocol.md).
 *
 * 1. Regression: with a seeded random source, the app's own crypto reproduces the vectors byte for byte.
 * 2. Independent: a second implementation built only on Node's `crypto` (X25519, HKDF, AES-GCM, HMAC,
 *    Argon2id), written from the spec and sharing no code with the app, opens and recomputes every vector.
 *
 * Regenerate after an intended format change: UPDATE_VECTORS=1 npx jest protocolVectors
 */
import { mnemonicToEntropy } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';

import {
  createBundle,
  encryptField,
  fingerprint,
  generateKeyPair,
  generateRecoveryPhrase,
  generateVaultKey,
  wrapVaultKey,
  wrapVaultKeyAuth,
} from '../sealed';
import { reflectionAad } from '../sealedDecrypt';
import { encryptMedia, MEDIA_CHUNK } from '../sealedMedia';
import { memoryTokens } from '../sealedSearch';

/* eslint-disable @typescript-eslint/no-require-imports */
// Node built-ins, loaded with require and typed loosely: the app's tsconfig deliberately has no Node
// globals (React Native doesn't have them), and the verifier below must not share code with the app.
const nc: any = require('node:crypto');
const { readFileSync, writeFileSync }: {
  readFileSync: (path: string, encoding: string) => string;
  writeFileSync: (path: string, data: string) => void;
} = require('node:fs');
const { join }: { join: (...parts: string[]) => string } = require('node:path');
const { Buffer }: { Buffer: any } = require('node:buffer');
declare const __dirname: string;
/* eslint-enable @typescript-eslint/no-require-imports */

// Without Web Crypto in the test environment the app falls back to expo-crypto; back that with Node's.
// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('expo-crypto', () => ({
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  getRandomValues: <T extends Uint8Array>(a: T) => require('node:crypto').webcrypto.getRandomValues(a),
}));

/**
 * Seeded DRBG, SHA-256("lock-test-vectors-v1" || counter) blocks, installed on the object noble reads
 * (globalThis.crypto) only while `run` executes, so the vectors are reproducible.
 */
async function seeded<T>(run: () => Promise<T>): Promise<T> {
  let counter = 0;
  const spy = jest.spyOn(globalThis.crypto, 'getRandomValues').mockImplementation(((a: Uint8Array) => {
    let off = 0;
    while (off < a.length) {
      const block = nc.createHash('sha256').update('lock-test-vectors-v1').update(String(counter++)).digest();
      const n = Math.min(block.length, a.length - off);
      a.set(block.subarray(0, n), off);
      off += n;
    }
    return a;
  }) as typeof globalThis.crypto.getRandomValues);
  try {
    return await run();
  } finally {
    spy.mockRestore();
  }
}

const VECTORS = join(__dirname, '..', '..', '..', '..', 'docs', 'sealed-vault-test-vectors.json');

const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');
const unhex = (s: string) => new Uint8Array(Buffer.from(s, 'hex'));
const b64ToHex = (s: string) => Buffer.from(s, 'base64').toString('hex');
const utf8 = (s: string) => new TextEncoder().encode(s);
const pattern = (n: number) => Uint8Array.from({ length: n }, (_, i) => i % 251);

const IDS = {
  vaultId: '0b6d6c0e-5a8f-4c1e-9d2a-1f3e5b7c9a01',
  memoryId: '3c9e1f7a-2b4d-4e6f-8a1c-5d7e9f0b2c13',
  reflectionId: '6a2b4c8d-1e3f-4a5b-9c7d-0e2f4a6b8c25',
  assetId: '9d1e3f5a-7b2c-4d6e-8f0a-2c4e6a8b0d37',
  aliceId: 'a11ce000-0000-4000-8000-000000000001',
  bobId: 'b0b00000-0000-4000-8000-000000000002',
  requestId: 'de71ce00-0000-4000-8000-000000000003',
};

async function generate() {
  return seeded(generateUnseeded);
}

async function generateUnseeded() {
  const alice = generateKeyPair();
  const bob = generateKeyPair();
  const device = generateKeyPair(); // one-time key of a new device (§7)
  const vaultKey = generateVaultKey();
  const passphrase = 'maple river cloud tiger';
  const recoveryPhrase = generateRecoveryPhrase();
  const { bundle } = await createBundle(passphrase, recoveryPhrase, alice);
  const { vaultId, memoryId, reflectionId, assetId, aliceId, bobId, requestId } = IDS;
  const aad = reflectionAad(memoryId, reflectionId, bobId);
  const big = encryptMedia(vaultKey, 'original', memoryId, assetId, pattern(MEDIA_CHUNK + 10));
  return {
    description:
      'Sealed Vault protocol test vectors. Spec: docs/sealed-vault-protocol.md. Byte strings are hex unless named *Base64. ' +
      'Randomness came from a seeded generator, so ciphertexts are reproducible; verify them by opening them with the keys below.',
    ids: IDS,
    keys: {
      alice: { secretKey: hex(alice.secretKey), publicKey: hex(alice.publicKey), fingerprint: fingerprint(alice.publicKey) },
      bob: { secretKey: hex(bob.secretKey), publicKey: hex(bob.publicKey), fingerprint: fingerprint(bob.publicKey) },
      newDevice: { secretKey: hex(device.secretKey), publicKey: hex(device.publicKey) },
      vaultKey: hex(vaultKey),
      vaultKeyVersion: 3,
    },
    accountBundle: {
      passphrase,
      recoveryPhrase,
      passphraseKdf: bundle.passphraseKdf,
      recoveryKdf: bundle.recoveryKdf,
      wrappedByPassphrase: b64ToHex(bundle.wrappedByPassphrase),
      wrappedByRecovery: b64ToHex(bundle.wrappedByRecovery),
    },
    vaultKeyWrapV2: hex(wrapVaultKeyAuth(vaultKey, bob.publicKey, vaultId, 3, bobId, { userId: aliceId, pair: alice })),
    vaultKeyWrapV1: hex(wrapVaultKey(vaultKey, bob.publicKey, vaultId, 3, bobId)),
    contentTitle: { plaintext: 'The night the power went out', ciphertext: b64ToHex(encryptField(vaultKey, memoryId, 'title', 'The night the power went out')) },
    reflectionV2: {
      plaintext: 'Still makes me smile',
      authorId: bobId,
      ciphertext: b64ToHex(encryptField(vaultKey, aad.recordId, aad.field, 'Still makes me smile')),
    },
    mediaSmall: {
      plaintext: 'hello sealed media',
      ciphertext: hex(encryptMedia(vaultKey, 'original', memoryId, assetId, utf8('hello sealed media'))),
    },
    mediaTwoChunks: {
      plaintext: 'bytes i % 251 for i in 0..(1 MiB + 10)',
      header: hex(big.subarray(0, 16)),
      length: big.length,
      sha256: nc.createHash('sha256').update(big).digest('hex'),
    },
    searchTokens: { text: 'Café by the lake', tokensBase64: memoryTokens(vaultKey, 'Café by the lake').sort() },
    deviceApprovalWrap: hex(wrapVaultKey(alice.secretKey, device.publicKey, 'device-approval', 1, `${aliceId}:${requestId}`)),
  };
}

type Vectors = Awaited<ReturnType<typeof generateUnseeded>>;

// ---- An independent implementation of the spec, on Node's crypto only ---------------------------------

const PKCS8_X25519 = Buffer.from('302e020100300506032b656e04220420', 'hex');
const SPKI_X25519 = Buffer.from('302a300506032b656e032100', 'hex');

function x25519(secretKey: Uint8Array, publicKey: Uint8Array): Uint8Array {
  return nc.diffieHellman({
    privateKey: nc.createPrivateKey({ key: Buffer.concat([PKCS8_X25519, secretKey]), format: 'der', type: 'pkcs8' }),
    publicKey: nc.createPublicKey({ key: Buffer.concat([SPKI_X25519, publicKey]), format: 'der', type: 'spki' }),
  });
}

function x25519Public(secretKey: Uint8Array): Uint8Array {
  const priv = nc.createPrivateKey({ key: Buffer.concat([PKCS8_X25519, secretKey]), format: 'der', type: 'pkcs8' });
  return nc.createPublicKey(priv).export({ format: 'der', type: 'spki' }).subarray(SPKI_X25519.length);
}

const hkdf = (ikm: Uint8Array, salt: Uint8Array, info: string): Uint8Array => Buffer.from(nc.hkdfSync('sha256', ikm, salt, info, 32));

function gcmOpen(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array | string, sealed: Uint8Array): { toString(enc: string): string } & Uint8Array {
  const d = nc.createDecipheriv('aes-256-gcm', key, nonce);
  d.setAAD(typeof aad === 'string' ? Buffer.from(aad, 'utf8') : aad);
  d.setAuthTag(sealed.subarray(sealed.length - 16));
  return Buffer.concat([d.update(sealed.subarray(0, sealed.length - 16)), d.final()]);
}

function gcmSeal(key: Uint8Array, nonce: Uint8Array, aad: Uint8Array, plain: Uint8Array): Uint8Array {
  const c = nc.createCipheriv('aes-256-gcm', key, nonce);
  c.setAAD(aad);
  return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
}

function mediaNonce(index: number, last: boolean): Uint8Array {
  const n = Buffer.alloc(12);
  n.writeUInt32BE(index, 7);
  n[11] = last ? 1 : 0;
  return n;
}

const contentOpen = (key: Uint8Array, aad: string, blob: Uint8Array) => gcmOpen(key, blob.subarray(0, 12), aad, blob.subarray(12)).toString('utf8');

function searchTerms(text: string): string[] {
  const folded = text.normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase();
  const terms = new Set<string>();
  for (const w of new Set(folded.split(/[^\p{L}\p{N}]+/u).filter(Boolean))) {
    terms.add(`w:${w}`);
    for (let n = 3; n < w.length; n++) terms.add(`p:${w.slice(0, n)}`);
  }
  return [...terms];
}

// ---- Tests ---------------------------------------------------------------------------------------------

describe('Sealed Vault protocol test vectors', () => {
  jest.setTimeout(120_000); // Argon2id in plain JS
  let v: Vectors;

  beforeAll(async () => {
    const generated = await generate();
    if (process.env.UPDATE_VECTORS === '1') writeFileSync(VECTORS, `${JSON.stringify(generated, null, 2)}\n`);
    v = JSON.parse(readFileSync(VECTORS, 'utf8')) as Vectors;
    expect(JSON.parse(JSON.stringify(generated))).toEqual(v); // 1. the app reproduces the vectors exactly
  });

  test('§2 fingerprints and public keys', () => {
    for (const who of [v.keys.alice, v.keys.bob]) {
      expect(hex(x25519Public(unhex(who.secretKey)))).toBe(who.publicKey);
      const fp = nc.createHash('sha256').update(unhex(who.publicKey)).digest('hex').slice(0, 32);
      expect(who.fingerprint).toBe(fp.match(/.{4}/g)!.join(' '));
    }
  });

  (typeof nc.argon2Sync === 'function' ? test : test.skip)('§2 passphrase wrap (Argon2id)', () => {
    const k = v.accountBundle.passphraseKdf as { salt: string; m: number; t: number; p: number };
    const key: Uint8Array = nc.argon2Sync('argon2id', {
      message: Buffer.from(v.accountBundle.passphrase.normalize('NFKC'), 'utf8'),
      nonce: Buffer.from(k.salt, 'base64'),
      parallelism: k.p,
      tagLength: 32,
      memory: k.m,
      passes: k.t,
    });
    const blob = unhex(v.accountBundle.wrappedByPassphrase);
    expect(blob[0]).toBe(1);
    expect(hex(gcmOpen(key, blob.subarray(1, 13), 'lock:secret-key:passphrase:v1', blob.subarray(13)))).toBe(v.keys.alice.secretKey);
  });

  test('§2 recovery wrap (BIP-39 entropy + HKDF)', () => {
    const entropy = mnemonicToEntropy(v.accountBundle.recoveryPhrase, wordlist);
    const key = hkdf(entropy, Buffer.from((v.accountBundle.recoveryKdf as { salt: string }).salt, 'base64'), 'lock:recovery-wrap:v1');
    const blob = unhex(v.accountBundle.wrappedByRecovery);
    expect(hex(gcmOpen(key, blob.subarray(1, 13), 'lock:secret-key:recovery:v1', blob.subarray(13)))).toBe(v.keys.alice.secretKey);
  });

  test('§3.1 authenticated vault-key wrap (format 2)', () => {
    const blob = unhex(v.vaultKeyWrapV2);
    expect([blob.length, blob[0]]).toEqual([93, 2]);
    const eph = blob.subarray(1, 33);
    const [bobSk, bobPk, alicePk] = [unhex(v.keys.bob.secretKey), unhex(v.keys.bob.publicKey), unhex(v.keys.alice.publicKey)];
    const info = `lock:vault-key:v2:${v.ids.vaultId}:3:${v.ids.bobId}:${v.ids.aliceId}`;
    const key = hkdf(Buffer.concat([x25519(bobSk, eph), x25519(bobSk, alicePk)]), Buffer.concat([eph, bobPk, alicePk]), info);
    expect(hex(gcmOpen(key, blob.subarray(33, 45), info, blob.subarray(45)))).toBe(v.keys.vaultKey);
  });

  test('§3.2 legacy vault-key wrap (format 1)', () => {
    const blob = unhex(v.vaultKeyWrapV1);
    expect([blob.length, blob[0]]).toEqual([93, 1]);
    const eph = blob.subarray(1, 33);
    const info = `lock:vault-key:v1:${v.ids.vaultId}:3:${v.ids.bobId}`;
    const key = hkdf(x25519(unhex(v.keys.bob.secretKey), eph), Buffer.concat([eph, unhex(v.keys.bob.publicKey)]), info);
    expect(hex(gcmOpen(key, blob.subarray(33, 45), info, blob.subarray(45)))).toBe(v.keys.vaultKey);
  });

  test('§4 content field and reflection (format 2) AAD', () => {
    const k = unhex(v.keys.vaultKey);
    expect(contentOpen(k, `${v.ids.memoryId}:title`, unhex(v.contentTitle.ciphertext))).toBe(v.contentTitle.plaintext);
    const aad = `${v.ids.memoryId}:${v.ids.reflectionId}:reflection:v2:${v.reflectionV2.authorId}`;
    expect(contentOpen(k, aad, unhex(v.reflectionV2.ciphertext))).toBe(v.reflectionV2.plaintext);
  });

  test('§5 media format 2: one chunk opened, two chunks re-encrypted from the spec', () => {
    const k = unhex(v.keys.vaultKey);
    const info = `lock:media:v2:original:${v.ids.memoryId}:${v.ids.assetId}`;

    const small = unhex(v.mediaSmall.ciphertext);
    const header = small.subarray(0, 16);
    expect(Buffer.from(header.subarray(0, 4)).toString('ascii')).toBe('LKM1');
    expect(header[4]).toBe(2);
    const fileKey = hkdf(k, header.subarray(5, 16), info);
    expect(gcmOpen(fileKey, mediaNonce(0, true), header, small.subarray(16)).toString('utf8')).toBe(v.mediaSmall.plaintext);

    const bigHeader = unhex(v.mediaTwoChunks.header);
    const bigKey = hkdf(k, bigHeader.subarray(5, 16), info);
    const plain = pattern(MEDIA_CHUNK + 10);
    const rebuilt = Buffer.concat([
      bigHeader,
      gcmSeal(bigKey, mediaNonce(0, false), bigHeader, plain.subarray(0, MEDIA_CHUNK)),
      gcmSeal(bigKey, mediaNonce(1, true), bigHeader, plain.subarray(MEDIA_CHUNK)),
    ]);
    expect(rebuilt.length).toBe(v.mediaTwoChunks.length);
    expect(nc.createHash('sha256').update(rebuilt).digest('hex')).toBe(v.mediaTwoChunks.sha256);
  });

  test('§6 blind search tokens', () => {
    const searchKey = nc.createHmac('sha256', unhex(v.keys.vaultKey)).update('lock:search-index:v1').digest();
    const tokens = searchTerms(v.searchTokens.text)
      .map((t) => nc.createHmac('sha256', searchKey).update(t, 'utf8').digest().subarray(0, 16).toString('base64'))
      .sort();
    expect(tokens).toEqual(v.searchTokens.tokensBase64);
  });

  test('§7 device approval box', () => {
    const blob = unhex(v.deviceApprovalWrap);
    const eph = blob.subarray(1, 33);
    const info = `lock:vault-key:v1:device-approval:1:${v.ids.aliceId}:${v.ids.requestId}`;
    const key = hkdf(x25519(unhex(v.keys.newDevice.secretKey), eph), Buffer.concat([eph, unhex(v.keys.newDevice.publicKey)]), info);
    const sk = gcmOpen(key, blob.subarray(33, 45), info, blob.subarray(45));
    expect(hex(sk)).toBe(v.keys.alice.secretKey);
    expect(hex(x25519Public(sk))).toBe(v.keys.alice.publicKey); // the new device's check
  });
});
