/**
 * Decryption of sealed vault content with the vault keys this device has unwrapped (roadmap 3.6).
 * Kept free of API imports so the API layer can use it. Without the key, content stays encrypted
 * (the plaintext fields stay null) rather than failing the whole request.
 */
import type { Coordinates, Memory, Reflection } from '@/api/types';

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

/** Stored form of a map position (roadmap 5.1): "lat,lng" with 6 decimals, encrypted like the other fields. */
export function formatCoordinates(c: Coordinates | null | undefined): string | null {
  return c ? `${c.latitude.toFixed(6)},${c.longitude.toFixed(6)}` : null;
}

export function parseCoordinates(stored: string | null | undefined): Coordinates | null {
  if (!stored) return null;
  const [lat, lng] = stored.split(',').map(Number);
  return Number.isFinite(lat) && Number.isFinite(lng) ? { latitude: lat, longitude: lng } : null;
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
    coordinates: parseCoordinates(decryptOptional(key, m.id, 'coordinates', e.coordinates)),
  };
}

/**
 * Reflection AAD (security review F5): v2 binds the memory and the author, so the server can't move a
 * reflection under another memory or credit it to someone else. Reflections written before v2 used
 * `reflectionId:reflection` and still open, without that binding.
 */
export function reflectionAad(memoryId: string, reflectionId: string, authorId: string) {
  return { recordId: `${memoryId}:${reflectionId}`, field: `reflection:v2:${authorId}` };
}

export function decryptReflection(vaultId: string, memoryId: string, r: Reflection): Reflection {
  if (!r.sealedBody || r.keyVersion == null || !hasVaultKey(vaultId, r.keyVersion)) return r;
  const key = vaultKey(vaultId, r.keyVersion);
  const aad = reflectionAad(memoryId, r.id, r.authorId);
  try {
    return { ...r, body: decryptField(key, aad.recordId, aad.field, r.sealedBody) };
  } catch {
    return { ...r, body: decryptField(key, r.id, 'reflection', r.sealedBody) }; // legacy (pre-v2) reflection
  }
}

