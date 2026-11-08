/**
 * Sealed Vault glue between the crypto (lib/sealed.ts) and the API (roadmap 3.6). Holds the unlocked
 * key pair for this device, unwraps vault keys on demand, creates key versions, and encrypts/decrypts
 * memories. Nothing here sends plaintext or secret keys to the server.
 */
import './cryptoRandom';
import { x25519 } from '@noble/curves/ed25519';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { Memory, MemoryInput, Vault, VaultKeyState } from '@/api/types';

import {
  decryptMemory,
  decryptReflection,
  formatCoordinates,
  hasVaultKey,
  parseCoordinates,
  reflectionAad,
  vaultKey,
  vaultKeys,
} from './sealedDecrypt';
import { clearDecryptedMedia } from './sealedFiles';
import { passphraseStrength } from './passphraseStrength';
import { acceptLegacyWrap, KeyChangedError, observeMemberKeys, senderKey } from './keyPins';
import { memoryTokens, queryTokens } from './sealedSearch';
import {
  SealedError,
  createBundle,
  decryptField,
  decryptOptional,
  encryptField,
  encryptOptional,
  fromBase64,
  generateVaultKey,
  rewrapPassphrase,
  toBase64,
  unlockWithPassphrase,
  unlockWithRecoveryPhrase,
  unwrapVaultKey,
  unwrapVaultKeyAuth,
  VAULT_KEY_FORMAT_V2,
  wrapFormat,
  wrapVaultKeyAuth,
  type KeyPair,
} from './sealed';

const STORE_KEY = 'lock.sealed.secretKey';

let pair: KeyPair | null = null;
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((l) => l());
}

export function onKeysChanged(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ---- This device's key pair ------------------------------------------------------------------

/**
 * The secret key is kept in the Keychain/Keystore on native, so the passphrase is needed once per
 * device. On web it lives in memory only (lost on reload): sealed vaults are native-first (decisions §24).
 */
async function persist(secretKey: Uint8Array | null) {
  if (Platform.OS === 'web') return;
  if (secretKey) await SecureStore.setItemAsync(STORE_KEY, toBase64(secretKey), { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY });
  else await SecureStore.deleteItemAsync(STORE_KEY);
}

/** Loads a key pair saved on this device earlier, checked against the account's public key. */
export async function restoreDeviceKeys(publicKey: string): Promise<boolean> {
  if (pair && toBase64(pair.publicKey) === publicKey) return true;
  if (Platform.OS === 'web') return false;
  const stored = await SecureStore.getItemAsync(STORE_KEY);
  if (!stored) return false;
  const secretKey = fromBase64(stored);
  const derived = x25519.getPublicKey(secretKey);
  if (toBase64(derived) !== publicKey) {
    await persist(null); // keys of another account signed in on this device before
    return false;
  }
  pair = { secretKey, publicKey: derived };
  notify();
  return true;
}

export function deviceUnlocked(): boolean {
  return pair !== null;
}

async function adopt(next: KeyPair) {
  pair = next;
  vaultKeys.clear();
  await persist(next.secretKey);
  notify();
}

/**
 * The server sees the account password at every sign-in, so a passphrase equal to it would let the
 * server unwrap the key bundle it stores (security review F3). Refused everywhere a passphrase is set.
 */
export function requireDistinctPassphrase(password: string, passphrase: string) {
  if (password.normalize('NFKC') === passphrase.normalize('NFKC')) {
    throw new SealedError('Choose a Sealed Vault passphrase that is different from your account password');
  }
  // Review F9: the wrapped key is an offline-guessing target, so the passphrase must carry real entropy.
  if (!passphraseStrength(passphrase).acceptable) {
    throw new SealedError('That passphrase is too easy to guess. Try four or five unrelated words.');
  }
}

/** First-time setup: creates the key pair and uploads it wrapped by the passphrase and the phrase. */
export async function setUpKeys(password: string, passphrase: string, recoveryPhrase: string, mfaCode?: string) {
  requireDistinctPassphrase(password, passphrase);
  const { bundle, pair: created } = await createBundle(passphrase, recoveryPhrase);
  const saved = await api.createKeyBundle(password, bundle, mfaCode);
  await adopt(created);
  return saved;
}

export async function unlockWithPassphraseOnDevice(passphrase: string) {
  await adopt(await unlockWithPassphrase(await api.keyBundle(), passphrase));
}

/** Lost passphrase: the recovery phrase opens the key, then a new passphrase replaces the old wrap. */
export async function recoverWithPhrase(password: string, phrase: string, newPassphrase: string, mfaCode?: string) {
  requireDistinctPassphrase(password, newPassphrase);
  const recovered = unlockWithRecoveryPhrase(await api.keyBundle(), phrase);
  await api.changeKeyPassphrase(password, await rewrapPassphrase(recovered.secretKey, newPassphrase), mfaCode);
  await adopt(recovered);
}

export async function changePassphrase(password: string, newPassphrase: string, mfaCode?: string) {
  if (!pair) throw new SealedError('Unlock your Sealed Vault keys first');
  requireDistinctPassphrase(password, newPassphrase);
  await api.changeKeyPassphrase(password, await rewrapPassphrase(pair.secretKey, newPassphrase), mfaCode);
}

/** Sign-out: forget every key, and every decrypted media copy, on this device. */
export async function forgetDeviceKeys() {
  pair = null;
  vaultKeys.clear();
  clearDecryptedMedia();
  await persist(null);
  notify();
}

/** The approving side of 3.6e needs the account secret key; only while unlocked. */
export function unlockedSecretKey(): Uint8Array {
  return requirePair().secretKey;
}

/** The new-device side of 3.6e: adopt keys an unlocked device sent (already checked by deviceApproval.collect). */
export async function adoptApprovedKeys(next: KeyPair) {
  await adopt(next);
}

// ---- Vault keys ----------------------------------------------------------------------------------

function requirePair(): KeyPair {
  if (!pair) throw new SealedError('Unlock your Sealed Vault keys to open this vault');
  return pair;
}

/**
 * Makes sure this device can read and write the vault: unwraps every version it holds, creates the
 * current version if nobody has yet (new vault, or after a member left), and shares it with members
 * who joined since. Returns the current key version. Throws SEALED_KEY_PENDING-style errors as SealedError.
 */
export async function openVault(vault: Vault, myUserId: string): Promise<number> {
  try {
    return await openVaultOnce(vault, myUserId);
  } catch (e) {
    // Another member's device created or shared the same version a moment earlier: use theirs.
    if (e instanceof ApiError && e.status === 409 && (e.code === 'KEY_EXISTS' || e.code === 'MEMBERS_MISSING')) {
      return openVaultOnce(vault, myUserId);
    }
    throw e;
  }
}

async function openVaultOnce(vault: Vault, myUserId: string): Promise<number> {
  const keys = requirePair();
  let state: VaultKeyState = await api.vaultKeys(vault.id);

  // Security review F1: the server is the key directory, so check it against what this device knows.
  const listedForMe = state.members.find((m) => m.userId === myUserId);
  if (listedForMe && listedForMe.publicKey !== toBase64(keys.publicKey)) {
    throw new SealedError('The server lists a different Sealed Vault key for your account, so this device won’t share keys. Contact support.');
  }
  const changed = await observeMemberKeys(myUserId, state.members.filter((m) => m.userId !== myUserId));

  let currentError: unknown = null;
  for (const k of state.myKeys) {
    const id = `${vault.id}:${k.keyVersion}`;
    if (vaultKeys.has(id)) continue;
    try {
      const key = await unwrapMine(k, keys, vault.id, myUserId, state.currentVersion);
      if (key) vaultKeys.set(id, key);
    } catch (e) {
      if (k.keyVersion === state.currentVersion) currentError = e; // older versions: keep what does open
    }
  }
  if (currentError) throw currentError;

  const current = state.currentVersion;
  const holdsCurrent = hasVaultKey(vault.id, current);
  // Nobody holds it yet: every active member is still missing it (members lists only those with keys).
  const nobodyHasCurrent = state.myKeys.every((k) => k.keyVersion !== current) && !holdsCurrent &&
    state.members.every((m) => state.missingCurrent.includes(m.userId));

  if (!holdsCurrent && nobodyHasCurrent) {
    // First device here since the version changed: generate it and wrap it for every active member.
    // A member whose key changed blocks this until the user accepts or rejects the new key.
    const blocked = state.missingCurrent.find((id) => changed.has(id));
    if (blocked) throw new KeyChangedError(blocked);
    const fresh = generateVaultKey();
    state = await api.addVaultKeys(vault.id, current, wrapFor(state, state.missingCurrent, fresh, vault.id, current, myUserId, keys));
    vaultKeys.set(`${vault.id}:${current}`, fresh);
  } else if (holdsCurrent && state.missingCurrent.length > 0) {
    // Someone joined since: share the current key with them (never to a key that changed under its pin).
    const key = vaultKey(vault.id, current);
    const sharable = state.missingCurrent.filter((id) => !changed.has(id) && state.members.some((m) => m.userId === id));
    if (sharable.length > 0) await api.addVaultKeys(vault.id, current, wrapFor(state, sharable, key, vault.id, current, myUserId, keys));
  }
  notify();
  return current;
}

/**
 * Opens one of my wrapped copies. Format 2 is verified against the wrapping member's pinned key
 * (review F2); format 1 (anonymous, from before) only up to the version this device first saw.
 */
async function unwrapMine(
  k: VaultKeyState['myKeys'][number],
  keys: KeyPair,
  vaultId: string,
  myUserId: string,
  currentVersion: number,
): Promise<Uint8Array | null> {
  const blob = fromBase64(k.wrappedKey);
  if (wrapFormat(blob) === VAULT_KEY_FORMAT_V2) {
    const sender = k.wrappedBy === myUserId ? keys.publicKey : await senderKey(myUserId, k.wrappedBy, k.wrappedByPublicKey);
    if (!sender) return null; // nothing to verify it with
    return unwrapVaultKeyAuth(blob, keys, vaultId, k.keyVersion, myUserId, { userId: k.wrappedBy, publicKey: sender });
  }
  if (!(await acceptLegacyWrap(myUserId, vaultId, k.keyVersion, currentVersion))) return null;
  return unwrapVaultKey(blob, keys, vaultId, k.keyVersion, myUserId);
}

function wrapFor(
  state: VaultKeyState,
  recipients: string[],
  key: Uint8Array,
  vaultId: string,
  version: number,
  myUserId: string,
  keys: KeyPair,
) {
  const out: Record<string, string> = {};
  for (const userId of recipients) {
    const member = state.members.find((m) => m.userId === userId);
    if (!member) throw new SealedError('A member has not set up Sealed Vault keys yet');
    out[userId] = toBase64(
      wrapVaultKeyAuth(key, fromBase64(member.publicKey), vaultId, version, userId, { userId: myUserId, pair: keys }),
    );
  }
  return out;
}


// ---- Content ------------------------------------------------------------------------------------

function sealedFields(vaultId: string, version: number, id: string, input: MemoryInput) {
  const key = vaultKey(vaultId, version);
  return {
    keyVersion: version,
    title: encryptField(key, id, 'title', input.title),
    thought: encryptOptional(key, id, 'thought', input.thought),
    feeling: encryptOptional(key, id, 'feeling', input.feeling),
    location: encryptOptional(key, id, 'location', input.location),
    coordinates: encryptOptional(key, id, 'coordinates', formatCoordinates(input.coordinates)),
    // 3.6f: blind search tokens, computed here with a key the server never has.
    searchTokens: memoryTokens(key, input.title, input.thought, input.feeling, input.location),
  };
}

/** Sealed vault search (3.6f): tokens under every key version this device holds for the vault. */
export async function searchSealed(vaultId: string, query: string) {
  const keys = [...vaultKeys.entries()].filter(([id]) => id.startsWith(`${vaultId}:`)).map(([, k]) => k);
  const words = queryTokens(keys, query);
  return words.length === 0 ? [] : api.searchSealed(vaultId, words);
}

/** The request body for a new sealed memory: encrypted here, safe to keep on the device while offline. */
export type SealedDraftBody = {
  id: string;
  occurredAt?: string | null;
  revealAt?: string | null;
  sealed: ReturnType<typeof sealedFields>;
};

export function buildSealedDraft(vault: Vault, version: number, input: MemoryInput): SealedDraftBody {
  const id = Crypto.randomUUID(); // the ciphertext is bound to this id, so the client picks it
  return { id, occurredAt: input.occurredAt, revealAt: input.revealAt, sealed: sealedFields(vault.id, version, id, input) };
}

export async function createSealedMemory(vault: Vault, version: number, input: MemoryInput, idempotencyKey: string) {
  return sendSealedDraft(vault.id, buildSealedDraft(vault, version, input), idempotencyKey);
}

export async function sendSealedDraft(vaultId: string, body: SealedDraftBody, idempotencyKey: string) {
  return decryptMemory(await api.createSealedMemory(vaultId, body, idempotencyKey));
}

/**
 * A queued sealed draft whose key version went stale while offline (a member left): decrypt it with the
 * old key and encrypt it again under the newest version this device holds. Null if the device lacks
 * either key; the user then opens the vault (which fetches its keys) and retries.
 */
export function reencryptSealedDraft(vaultId: string, body: SealedDraftBody): SealedDraftBody | null {
  const old = body.sealed.keyVersion;
  const newest = Math.max(...[...vaultKeys.keys()].filter((k) => k.startsWith(`${vaultId}:`)).map((k) => Number(k.split(':')[1])));
  if (!Number.isFinite(newest) || newest <= old || !hasVaultKey(vaultId, old)) return null;
  const key = vaultKey(vaultId, old);
  const input = {
    title: decryptField(key, body.id, 'title', body.sealed.title),
    thought: decryptOptional(key, body.id, 'thought', body.sealed.thought),
    feeling: decryptOptional(key, body.id, 'feeling', body.sealed.feeling),
    location: decryptOptional(key, body.id, 'location', body.sealed.location),
    coordinates: parseCoordinates(decryptOptional(key, body.id, 'coordinates', body.sealed.coordinates)),
    occurredAt: body.occurredAt,
    revealAt: body.revealAt,
  } as MemoryInput;
  return { ...body, sealed: sealedFields(vaultId, newest, body.id, input) };
}

export async function updateSealedDraft(memory: Memory, version: number, input: MemoryInput) {
  const body = { occurredAt: input.occurredAt, revealAt: input.revealAt, sealed: sealedFields(memory.vaultId, version, memory.id, input) };
  return decryptMemory(await api.updateSealedDraft(memory.id, body));
}

export async function addSealedReflection(vaultId: string, version: number, memoryId: string, body: string, authorId: string) {
  const id = Crypto.randomUUID();
  const aad = reflectionAad(memoryId, id, authorId); // bound to this memory and author (review F5)
  const sealedBody = encryptField(vaultKey(vaultId, version), aad.recordId, aad.field, body);
  return decryptReflection(vaultId, memoryId, await api.addSealedReflection(memoryId, { id, keyVersion: version, sealedBody }));
}

/**
 * Indexes this user's sealed memories written before on-device search existed (follow-up to 3.6f).
 * Each memory gets tokens under the key version it is encrypted with, so the existing search (which
 * tries every version the device holds) finds it. Memories with no words are reported as empty and
 * stop being offered. Runs at most once per vault per session; failures are retried next session.
 */
const reindexed = new Set<string>();

export async function reindexSealedVault(vaultId: string): Promise<number> {
  if (reindexed.has(vaultId)) return 0;
  reindexed.add(vaultId);
  let done = 0;
  try {
    for (let round = 0; round < 20; round++) {
      const ids = await api.unindexedSealed(vaultId);
      let progressed = false;
      for (const id of ids) {
        const m = await api.memory(id); // decrypted by the API layer when the key is held
        const version = m.encrypted?.keyVersion;
        if (version == null || !hasVaultKey(vaultId, version) || m.title === null) continue;
        await api.replaceSearchTokens(id, memoryTokens(vaultKey(vaultId, version), m.title, m.thought, m.feeling, m.location));
        done++;
        progressed = true;
      }
      if (ids.length < 100 || !progressed) break;
    }
  } catch {
    reindexed.delete(vaultId); // try again the next time the vault opens
  }
  return done;
}

export { decryptMemory, decryptReflection };
