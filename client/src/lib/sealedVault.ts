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

import { decryptMemory, decryptReflection, hasVaultKey, vaultKey, vaultKeys } from './sealedDecrypt';
import { clearDecryptedMedia } from './sealedFiles';
import { memoryTokens, queryTokens } from './sealedSearch';
import {
  SealedError,
  createBundle,
  encryptField,
  encryptOptional,
  fromBase64,
  generateVaultKey,
  rewrapPassphrase,
  toBase64,
  unlockWithPassphrase,
  unlockWithRecoveryPhrase,
  unwrapVaultKey,
  wrapVaultKey,
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

/** First-time setup: creates the key pair and uploads it wrapped by the passphrase and the phrase. */
export async function setUpKeys(password: string, passphrase: string, recoveryPhrase: string, mfaCode?: string) {
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
  const recovered = unlockWithRecoveryPhrase(await api.keyBundle(), phrase);
  await api.changeKeyPassphrase(password, await rewrapPassphrase(recovered.secretKey, newPassphrase), mfaCode);
  await adopt(recovered);
}

export async function changePassphrase(password: string, newPassphrase: string, mfaCode?: string) {
  if (!pair) throw new SealedError('Unlock your Sealed Vault keys first');
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
  for (const k of state.myKeys) {
    const id = `${vault.id}:${k.keyVersion}`;
    if (!vaultKeys.has(id)) vaultKeys.set(id, unwrapVaultKey(fromBase64(k.wrappedKey), keys, vault.id, k.keyVersion, myUserId));
  }
  const current = state.currentVersion;
  const holdsCurrent = hasVaultKey(vault.id, current);
  // Nobody holds it yet: every active member is still missing it (members lists only those with keys).
  const nobodyHasCurrent = state.myKeys.every((k) => k.keyVersion !== current) && !holdsCurrent &&
    state.members.every((m) => state.missingCurrent.includes(m.userId));

  if (!holdsCurrent && nobodyHasCurrent) {
    // First device here since the version changed: generate it and wrap it for every active member.
    const fresh = generateVaultKey();
    state = await api.addVaultKeys(vault.id, current, wrapFor(state, state.missingCurrent, fresh, vault.id, current));
    vaultKeys.set(`${vault.id}:${current}`, fresh);
  } else if (holdsCurrent && state.missingCurrent.length > 0) {
    // Someone joined since: share the current key with them.
    const key = vaultKey(vault.id, current);
    const sharable = state.missingCurrent.filter((id) => state.members.some((m) => m.userId === id));
    if (sharable.length > 0) await api.addVaultKeys(vault.id, current, wrapFor(state, sharable, key, vault.id, current));
  }
  notify();
  return current;
}

function wrapFor(state: VaultKeyState, recipients: string[], key: Uint8Array, vaultId: string, version: number) {
  const out: Record<string, string> = {};
  for (const userId of recipients) {
    const member = state.members.find((m) => m.userId === userId);
    if (!member) throw new SealedError('A member has not set up Sealed Vault keys yet');
    out[userId] = toBase64(wrapVaultKey(key, fromBase64(member.publicKey), vaultId, version, userId));
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

export async function createSealedMemory(vault: Vault, version: number, input: MemoryInput, idempotencyKey: string) {
  const id = Crypto.randomUUID(); // the ciphertext is bound to this id, so the client picks it
  const body = { id, occurredAt: input.occurredAt, revealAt: input.revealAt, sealed: sealedFields(vault.id, version, id, input) };
  return decryptMemory(await api.createSealedMemory(vault.id, body, idempotencyKey));
}

export async function updateSealedDraft(memory: Memory, version: number, input: MemoryInput) {
  const body = { occurredAt: input.occurredAt, revealAt: input.revealAt, sealed: sealedFields(memory.vaultId, version, memory.id, input) };
  return decryptMemory(await api.updateSealedDraft(memory.id, body));
}

export async function addSealedReflection(vaultId: string, version: number, memoryId: string, body: string) {
  const id = Crypto.randomUUID();
  const sealedBody = encryptField(vaultKey(vaultId, version), id, 'reflection', body);
  return decryptReflection(vaultId, await api.addSealedReflection(memoryId, { id, keyVersion: version, sealedBody }));
}

export { decryptMemory, decryptReflection };
