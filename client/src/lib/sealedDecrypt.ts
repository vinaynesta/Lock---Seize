/**
 * Decryption of sealed vault content with the vault keys this device has unwrapped (roadmap 3.6).
 * Kept free of API imports so the API layer can use it. Without the key, content stays encrypted
 * (the plaintext fields stay null) rather than failing the whole request.
 */
import type { Memory, Reflection } from '@/api/types';

import { SealedError, decryptField, decryptOptional } from './sealed';

/** Unwrapped vault keys for this session: `${vaultId}:${version}` → key. Never persisted. */
export const vaultKeys = new Map<string, Uint8Array>();

export function hasVaultKey(vaultId: string, version: number): boolean {
  return vaultKeys.has(`${vaultId}:${version}`);
}

export function vaultKey(vaultId: string, version: number): Uint8Array {
  const key = vaultKeys.get(`${vaultId}:${version}`);
  if (!key) throw new SealedError("This device doesn't have the key for this memory yet");
  return key;
}

/** Fills title/thought/feeling/location from `encrypted`. Leaves them null if this device lacks the key. */
export function decryptMemory(m: Memory): Memory {
  const e = m.encrypted;
  if (!e || !hasVaultKey(m.vaultId, e.keyVersion)) return m;
  const key = vaultKey(m.vaultId, e.keyVersion);
  return {
    ...m,
    title: decryptField(key, m.id, 'title', e.title),
    thought: decryptOptional(key, m.id, 'thought', e.thought),
    feeling: decryptOptional(key, m.id, 'feeling', e.feeling),
    location: decryptOptional(key, m.id, 'location', e.location),
  };
}

export function decryptReflection(vaultId: string, r: Reflection): Reflection {
  if (!r.sealedBody || r.keyVersion == null || !hasVaultKey(vaultId, r.keyVersion)) return r;
  return { ...r, body: decryptField(vaultKey(vaultId, r.keyVersion), r.id, 'reflection', r.sealedBody) };
}

