/**
 * Pinned member keys for Sealed Vaults (security review F1). The server is the key directory, so a
 * device remembers each member's public key the first time it sees it (trust on first use) and from
 * then on refuses to wrap a vault key to, or accept a wrap from, a different key for that person until
 * the user compares fingerprints and accepts the new key. Members can also be marked verified after an
 * in-person fingerprint check.
 *
 * Pins are integrity data, not secrets: they live in AsyncStorage (per account), which also works on
 * web. An attacker who can rewrite app storage already controls the device.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';

import { fingerprint, fromBase64, SealedError } from './sealed';

export interface Pin {
  publicKey: string;
  verified: boolean;
}

interface PinFile {
  members: Record<string, Pin>;
  /** A different key the server presented for a pinned member, waiting for the user's decision. */
  changed: Record<string, string>;
  /** Highest key version per vault this device accepted as an unauthenticated (format-1) wrap. */
  v1Ceiling: Record<string, number>;
}

/** A member's key differs from the one this device pinned. */
export class KeyChangedError extends SealedError {
  constructor(readonly userId: string) {
    super('A member’s Sealed Vault key changed. Compare fingerprints with them under Members, then accept the new key.');
  }
}

const cache = new Map<string, PinFile>();
const listeners = new Set<() => void>();
const storageKey = (accountId: string) => `lock.keypins.v1.${accountId}`;

async function load(accountId: string): Promise<PinFile> {
  const hit = cache.get(accountId);
  if (hit) return hit;
  let file: PinFile = { members: {}, changed: {}, v1Ceiling: {} };
  try {
    const raw = await AsyncStorage.getItem(storageKey(accountId));
    if (raw) file = { ...file, ...(JSON.parse(raw) as Partial<PinFile>) };
  } catch {
    // unreadable: start over (only re-opens the trust-on-first-use window)
  }
  cache.set(accountId, file);
  return file;
}

async function save(accountId: string, file: PinFile) {
  cache.set(accountId, file);
  await AsyncStorage.setItem(storageKey(accountId), JSON.stringify(file));
  listeners.forEach((l) => l());
}

/**
 * Records the keys the server lists for a vault's members: pins unseen ones, notes changed ones.
 * Returns the ids whose key no longer matches their pin.
 */
export async function observeMemberKeys(accountId: string, members: { userId: string; publicKey: string }[]): Promise<Set<string>> {
  const file = await load(accountId);
  const changed = new Set<string>();
  let dirty = false;
  for (const m of members) {
    const pin = file.members[m.userId];
    if (!pin) {
      file.members[m.userId] = { publicKey: m.publicKey, verified: false };
      dirty = true;
    } else if (pin.publicKey !== m.publicKey) {
      changed.add(m.userId);
      if (file.changed[m.userId] !== m.publicKey) {
        file.changed[m.userId] = m.publicKey;
        dirty = true;
      }
    }
  }
  if (dirty) await save(accountId, file);
  return changed;
}

/**
 * The key to verify a wrap from `userId` with: the pinned one. If none is pinned yet, the key the
 * server stored with the wrap is pinned (trust on first use). A mismatch with the pin throws.
 */
export async function senderKey(accountId: string, userId: string, serverKey: string | null): Promise<Uint8Array | null> {
  const file = await load(accountId);
  const pin = file.members[userId];
  if (pin) {
    if (serverKey && serverKey !== pin.publicKey) throw new KeyChangedError(userId);
    return fromBase64(pin.publicKey);
  }
  if (!serverKey) return null;
  file.members[userId] = { publicKey: serverKey, verified: false };
  await save(accountId, file);
  return fromBase64(serverKey);
}

/** Format-1 (unauthenticated) wraps are accepted only up to the version first seen here (review F2). */
export async function acceptLegacyWrap(accountId: string, vaultId: string, version: number, currentVersion: number): Promise<boolean> {
  const file = await load(accountId);
  const ceiling = file.v1Ceiling[vaultId];
  if (ceiling === undefined) {
    // First time this device sees the vault since the upgrade: legacy copies up to today's version.
    file.v1Ceiling[vaultId] = currentVersion;
    await save(accountId, file);
    return version <= currentVersion;
  }
  return version <= ceiling;
}

export async function markVerified(accountId: string, userId: string) {
  const file = await load(accountId);
  const pin = file.members[userId];
  if (!pin || file.changed[userId]) return;
  await save(accountId, { ...file, members: { ...file.members, [userId]: { ...pin, verified: true } } });
}

/** The user compared fingerprints and accepts a member's new key (unverified until checked again). */
export async function acceptNewKey(accountId: string, userId: string) {
  const file = await load(accountId);
  const next = file.changed[userId];
  if (!next) return;
  const changed = { ...file.changed };
  delete changed[userId];
  await save(accountId, { ...file, changed, members: { ...file.members, [userId]: { publicKey: next, verified: false } } });
}

export type PinStatus =
  | { state: 'unpinned' }
  | { state: 'pinned' | 'verified'; fingerprint: string }
  | { state: 'changed'; fingerprint: string; newFingerprint: string };

/** For the Members screen: each member's pin status, kept up to date. */
export function usePinStatus(accountId: string | undefined): (userId: string) => PinStatus {
  const [file, setFile] = useState<PinFile | null>(null);
  useEffect(() => {
    if (!accountId) return;
    let live = true;
    const refresh = () => void load(accountId).then((f) => live && setFile({ ...f }));
    refresh();
    listeners.add(refresh);
    return () => {
      live = false;
      listeners.delete(refresh);
    };
  }, [accountId]);
  return (userId) => {
    const pin = file?.members[userId];
    if (!pin) return { state: 'unpinned' };
    const fp = fingerprint(fromBase64(pin.publicKey));
    const next = file?.changed[userId];
    if (next) return { state: 'changed', fingerprint: fp, newFingerprint: fingerprint(fromBase64(next)) };
    return { state: pin.verified ? 'verified' : 'pinned', fingerprint: fp };
  };
}
