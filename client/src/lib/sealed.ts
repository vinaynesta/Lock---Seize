/**
 * End-to-end encryption for Sealed Vaults (roadmap 3.6, pieces 1-3; docs/sealed-vault-design.md §3,
 * decisions §24). Everything here runs on the device; the server only ever receives the outputs.
 *
 *   user secret key (X25519, random)
 *     ├─ wrapped by Argon2id(passphrase)              → server, opaque
 *     └─ wrapped by HKDF(BIP-39 recovery phrase)      → server, opaque
 *   vault data key (AES-256, random, one per key version)
 *     └─ wrapped to each member's public key (X25519 ECDH + HKDF-SHA256 + AES-256-GCM)
 *   content: AES-256-GCM(vault key, AAD = "<recordId>:<field>")   (the server-side scheme, unchanged)
 *
 * Audited primitives only (@noble/curves, @noble/hashes, @noble/ciphers, @scure/bip39); no custom crypto.
 * Byte layouts are checked by the server (backend crypto/E2eFormats.java); keep both in step.
 */
import './cryptoRandom'; // must stay first: noble reads globalThis.crypto on load
import { gcm } from '@noble/ciphers/aes';
import { x25519 } from '@noble/curves/ed25519';
import { argon2idAsync } from '@noble/hashes/argon2';
import { hkdf } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';
import { randomBytes } from '@noble/hashes/utils';
import { entropyToMnemonic, mnemonicToEntropy, validateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';

export const FORMAT_V1 = 1;
const NONCE = 12;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

/**
 * OWASP's Argon2id minimum (19 MiB, 2 passes). Phones run this in JavaScript, where 64 MiB takes many
 * seconds; the passphrase is a second secret on top of the account password, and wrapped keys are only
 * served to the signed-in owner, so the floor is a reasonable trade. The server rejects anything weaker.
 */
export const DEFAULT_ARGON2 = { m: 19_456, t: 2, p: 1 } as const;

export interface Argon2Kdf {
  alg: 'argon2id';
  salt: string;
  m: number;
  t: number;
  p: number;
}
export interface HkdfKdf {
  alg: 'hkdf-sha256';
  salt: string;
}
export type Kdf = Argon2Kdf | HkdfKdf;

export class SealedError extends Error {}

// ---- Encoding -----------------------------------------------------------------------------------

export function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromBase64(value: string): Uint8Array {
  const s = atob(value);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

// ---- Piece 1: key pair, passphrase and recovery phrase ---------------------------------------------

export interface KeyPair {
  secretKey: Uint8Array;
  publicKey: Uint8Array;
}

export function generateKeyPair(): KeyPair {
  const secretKey = x25519.utils.randomSecretKey();
  return { secretKey, publicKey: x25519.getPublicKey(secretKey) };
}

/** 24 words (256 bits). The only way back in if the passphrase is forgotten and no device has the key. */
export function generateRecoveryPhrase(): string {
  return entropyToMnemonic(randomBytes(32), wordlist);
}

export function normalizePhrase(phrase: string): string {
  return phrase.trim().toLowerCase().split(/\s+/).join(' ');
}

export function isValidRecoveryPhrase(phrase: string): boolean {
  const normalized = normalizePhrase(phrase);
  return normalized.split(' ').length === 24 && validateMnemonic(normalized, wordlist);
}

async function deriveFromPassphrase(passphrase: string, kdf: Argon2Kdf): Promise<Uint8Array> {
  return argon2idAsync(encoder.encode(passphrase.normalize('NFKC')), fromBase64(kdf.salt), {
    m: kdf.m,
    t: kdf.t,
    p: kdf.p,
    dkLen: 32,
  });
}

/** The phrase already carries 256 bits of entropy, so HKDF suffices: no stretching needed. */
function deriveFromPhrase(phrase: string, kdf: HkdfKdf): Uint8Array {
  if (!isValidRecoveryPhrase(phrase)) throw new SealedError('That recovery phrase is not valid. Check each word.');
  const entropy = mnemonicToEntropy(normalizePhrase(phrase), wordlist);
  return hkdf(sha256, entropy, fromBase64(kdf.salt), 'lock:recovery-wrap:v1', 32);
}

function newArgon2Kdf(): Argon2Kdf {
  return { alg: 'argon2id', salt: toBase64(randomBytes(16)), ...DEFAULT_ARGON2 };
}

function newHkdfKdf(): HkdfKdf {
  return { alg: 'hkdf-sha256', salt: toBase64(randomBytes(16)) };
}

/** {@code 0x01 || nonce || AES-GCM(secretKey)}, AAD binds it to its purpose. */
function wrapSecret(wrappingKey: Uint8Array, secretKey: Uint8Array, purpose: string): Uint8Array {
  const nonce = randomBytes(NONCE);
  const ct = gcm(wrappingKey, nonce, encoder.encode(`lock:secret-key:${purpose}:v1`)).encrypt(secretKey);
  return concat(Uint8Array.of(FORMAT_V1), nonce, ct);
}

function unwrapSecret(wrappingKey: Uint8Array, blob: Uint8Array, purpose: string, failure: string): Uint8Array {
  if (blob.length !== 61 || blob[0] !== FORMAT_V1) throw new SealedError('Unsupported key format');
  try {
    return gcm(wrappingKey, blob.subarray(1, 1 + NONCE), encoder.encode(`lock:secret-key:${purpose}:v1`)).decrypt(
      blob.subarray(1 + NONCE),
    );
  } catch {
    throw new SealedError(failure);
  }
}

export interface NewBundle {
  publicKey: string;
  wrappedByPassphrase: string;
  passphraseKdf: Argon2Kdf;
  wrappedByRecovery: string;
  recoveryKdf: HkdfKdf;
}

/** Everything the server stores for a new user; the key pair itself stays on this device. */
export async function createBundle(passphrase: string, recoveryPhrase: string, pair = generateKeyPair()) {
  const passphraseKdf = newArgon2Kdf();
  const recoveryKdf = newHkdfKdf();
  const bundle: NewBundle = {
    publicKey: toBase64(pair.publicKey),
    wrappedByPassphrase: toBase64(wrapSecret(await deriveFromPassphrase(passphrase, passphraseKdf), pair.secretKey, 'passphrase')),
    passphraseKdf,
    wrappedByRecovery: toBase64(wrapSecret(deriveFromPhrase(recoveryPhrase, recoveryKdf), pair.secretKey, 'recovery')),
    recoveryKdf,
  };
  return { bundle, pair };
}

export interface StoredBundle {
  publicKey: string;
  wrappedByPassphrase: string;
  passphraseKdf: Kdf;
  wrappedByRecovery: string;
  recoveryKdf: Kdf;
}

function checkPublicKey(bundle: StoredBundle, secretKey: Uint8Array): KeyPair {
  const publicKey = x25519.getPublicKey(secretKey);
  if (toBase64(publicKey) !== bundle.publicKey) throw new SealedError('These keys do not belong to this account');
  return { secretKey, publicKey };
}

export async function unlockWithPassphrase(bundle: StoredBundle, passphrase: string): Promise<KeyPair> {
  if (bundle.passphraseKdf.alg !== 'argon2id') throw new SealedError('Unsupported key format');
  const key = await deriveFromPassphrase(passphrase, bundle.passphraseKdf);
  return checkPublicKey(
    bundle,
    unwrapSecret(key, fromBase64(bundle.wrappedByPassphrase), 'passphrase', 'That passphrase is not right.'),
  );
}

export function unlockWithRecoveryPhrase(bundle: StoredBundle, phrase: string): KeyPair {
  if (bundle.recoveryKdf.alg !== 'hkdf-sha256') throw new SealedError('Unsupported key format');
  const key = deriveFromPhrase(phrase, bundle.recoveryKdf);
  return checkPublicKey(
    bundle,
    unwrapSecret(key, fromBase64(bundle.wrappedByRecovery), 'recovery', 'That recovery phrase does not match this account.'),
  );
}

/** New passphrase wrap for the same secret key (change, or set a new one after recovery). */
export async function rewrapPassphrase(secretKey: Uint8Array, passphrase: string) {
  const passphraseKdf = newArgon2Kdf();
  return {
    wrappedByPassphrase: toBase64(wrapSecret(await deriveFromPassphrase(passphrase, passphraseKdf), secretKey, 'passphrase')),
    passphraseKdf,
  };
}

/** Groups of four hex digits of SHA-256(public key)[0..16]; matches the server's fingerprint. */
export function fingerprint(publicKey: Uint8Array): string {
  const hex = Array.from(sha256(publicKey).subarray(0, 16), (b) => b.toString(16).padStart(2, '0')).join('');
  return hex.match(/.{4}/g)!.join(' ');
}

// ---- Piece 2: vault data keys wrapped per member ---------------------------------------------------

export function generateVaultKey(): Uint8Array {
  return randomBytes(32);
}

function boxInfo(vaultId: string, version: number, recipientId: string): Uint8Array {
  return encoder.encode(`lock:vault-key:v1:${vaultId}:${version}:${recipientId}`);
}

/**
 * Sealed-box style: an ephemeral X25519 key per wrap, so only the recipient's secret key opens it. The
 * vault, version and recipient are bound in (HKDF info and AAD): a wrap can't be replayed for another
 * vault, version or member.
 */
export function wrapVaultKey(
  vaultKey: Uint8Array,
  recipientPublicKey: Uint8Array,
  vaultId: string,
  version: number,
  recipientId: string,
): Uint8Array {
  const ephemeral = x25519.utils.randomSecretKey();
  const ephemeralPublic = x25519.getPublicKey(ephemeral);
  const shared = x25519.getSharedSecret(ephemeral, recipientPublicKey);
  const info = boxInfo(vaultId, version, recipientId);
  const key = hkdf(sha256, shared, concat(ephemeralPublic, recipientPublicKey), info, 32);
  const nonce = randomBytes(NONCE);
  return concat(Uint8Array.of(FORMAT_V1), ephemeralPublic, nonce, gcm(key, nonce, info).encrypt(vaultKey));
}

export function unwrapVaultKey(
  wrapped: Uint8Array,
  pair: KeyPair,
  vaultId: string,
  version: number,
  recipientId: string,
): Uint8Array {
  if (wrapped.length !== 93 || wrapped[0] !== FORMAT_V1) throw new SealedError('Unsupported vault key format');
  const ephemeralPublic = wrapped.subarray(1, 33);
  const shared = x25519.getSharedSecret(pair.secretKey, ephemeralPublic);
  const info = boxInfo(vaultId, version, recipientId);
  const key = hkdf(sha256, shared, concat(ephemeralPublic, pair.publicKey), info, 32);
  try {
    return gcm(key, wrapped.subarray(33, 33 + NONCE), info).decrypt(wrapped.subarray(33 + NONCE));
  } catch {
    throw new SealedError('This vault key was not wrapped for you');
  }
}

// ---- Piece 3: content fields -------------------------------------------------------------------------

/** Same layout and AAD as the server's ContentCipher: nonce(12) || ciphertext+tag, AAD "recordId:field". */
export function encryptField(vaultKey: Uint8Array, recordId: string, field: string, plaintext: string): string {
  const nonce = randomBytes(NONCE);
  const ct = gcm(vaultKey, nonce, encoder.encode(`${recordId}:${field}`)).encrypt(encoder.encode(plaintext));
  return toBase64(concat(nonce, ct));
}

export function decryptField(vaultKey: Uint8Array, recordId: string, field: string, ciphertext: string): string {
  const blob = fromBase64(ciphertext);
  try {
    return decoder.decode(gcm(vaultKey, blob.subarray(0, NONCE), encoder.encode(`${recordId}:${field}`)).decrypt(blob.subarray(NONCE)));
  } catch {
    throw new SealedError('This content could not be decrypted');
  }
}

export function encryptOptional(vaultKey: Uint8Array, recordId: string, field: string, value: string | null | undefined) {
  return value == null || value === '' ? null : encryptField(vaultKey, recordId, field, value);
}

export function decryptOptional(vaultKey: Uint8Array, recordId: string, field: string, value: string | null | undefined) {
  return value == null ? null : decryptField(vaultKey, recordId, field, value);
}
