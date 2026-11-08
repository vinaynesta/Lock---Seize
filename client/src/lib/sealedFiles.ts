/**
 * Native file plumbing for sealed media (roadmap 3.6d/f): stream-encrypt a picked file into the cache,
 * upload it with the native uploader, and download + decrypt into the cache for viewing. Plaintext
 * copies live only in the app's cache directory and are deleted when no longer needed.
 */
import './cryptoRandom';
import { sha256 } from '@noble/hashes/sha2';
import * as Crypto from 'expo-crypto';
import { Directory, File, FileMode, Paths } from 'expo-file-system';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { Asset, AssetType } from '@/api/types';

import { SealedError, toBase64 } from './sealed';
import { MEDIA_CHUNK, MEDIA_HEADER, mediaDecryptor, mediaEncryptor, encryptMedia, type MediaPurpose } from './sealedMedia';
import { vaultKey } from './sealedDecrypt';

const TAG = 16;

function cacheFile(name: string): File {
  return new File(Paths.cache, `sealed-${name}`);
}

async function sha256Base64(file: File): Promise<string> {
  // expo-crypto has no streaming digest; @noble/hashes does.
  const h = sha256.create();
  const handle = file.open(FileMode.ReadOnly);
  try {
    for (;;) {
      const piece = await handle.readBytes(MEDIA_CHUNK);
      if (piece.length === 0) break;
      h.update(piece);
    }
  } finally {
    handle.close();
  }
  return toBase64(h.digest());
}

/**
 * Encrypts `source` chunk by chunk into a new cache file. Never holds the whole file in memory, and the
 * async file API (SDK 58) keeps the UI responsive while large videos are processed.
 */
async function encryptFile(
  source: File, key: Uint8Array, purpose: MediaPurpose, memoryId: string, assetId: string, into?: File,
): Promise<File> {
  const target = into ?? cacheFile(`${Crypto.randomUUID()}.lkm`);
  target.create();
  const enc = mediaEncryptor(key, purpose, memoryId, assetId);
  const input = source.open(FileMode.ReadOnly);
  const output = target.open(FileMode.WriteOnly);
  try {
    await output.writeBytes(enc.header);
    // Read one chunk ahead to find the last one, instead of trusting `size` (review I2: a missing size
    // would have marked the first chunk last and dropped the rest).
    let current = await input.readBytes(MEDIA_CHUNK);
    for (;;) {
      const next = await input.readBytes(MEDIA_CHUNK);
      const last = next.length === 0;
      await output.writeBytes(enc.chunk(current, last));
      if (last) break;
      current = next;
    }
  } finally {
    input.close();
    output.close();
  }
  return target;
}

/** Decrypts an encrypted cache file into a plaintext cache file, chunk by chunk. */
async function decryptFile(
  source: File, key: Uint8Array, purpose: MediaPurpose, memoryId: string, assetId: string, extension: string,
): Promise<File> {
  const target = cacheFile(`${Crypto.randomUUID()}${extension}`);
  target.create();
  const input = source.open(FileMode.ReadOnly);
  const output = target.open(FileMode.WriteOnly);
  try {
    const dec = mediaDecryptor(key, await input.readBytes(MEDIA_HEADER), purpose, memoryId, assetId);
    const total = (source.size ?? 0) - MEDIA_HEADER;
    let read = 0;
    while (read < total) {
      const piece = await input.readBytes(MEDIA_CHUNK + TAG);
      read += piece.length;
      await output.writeBytes(dec.chunk(piece, read >= total));
    }
    if (!dec.finished) throw new SealedError('This file is truncated'); // review F7: header-only file
  } catch (e) {
    output.close();
    target.delete();
    throw e;
  } finally {
    input.close();
    try {
      output.close();
    } catch {
      // already closed after a failure
    }
  }
  return target;
}

async function putFile(file: File, ticket: { uploadUrl: string; headers: Record<string, string> }) {
  const result = await file.upload(ticket.uploadUrl, { httpMethod: 'PUT', headers: ticket.headers });
  if (result.status < 200 || result.status >= 300) throw new Error(`Upload to storage failed (${result.status})`);
}

/** A sealed upload ready to send: ciphertext only (original, and a photo's thumbnail), bound to `assetId`. */
export interface PreparedSealedUpload {
  assetId: string;
  fileUri: string;
  thumb?: { fileUri: string; width: number; height: number };
}

/**
 * Encrypts a picked file, and for a photo a 256 px thumbnail, on the device. Writes only ciphertext:
 * into `dir` when given (the offline queue's persistent folder, roadmap 3.10), else into the cache. The
 * asset id is chosen here, because the encryption is bound to it (review F6).
 */
export async function prepareSealedUpload(
  vaultId: string,
  keyVersion: number,
  memoryId: string,
  uri: string,
  type: AssetType,
  dir?: Directory,
): Promise<PreparedSealedUpload> {
  const key = vaultKey(vaultId, keyVersion);
  const assetId = Crypto.randomUUID();
  const target = dir ? new File(dir, `sealed-${assetId}.lkm`) : undefined;
  const encrypted = await encryptFile(new File(uri), key, 'original', memoryId, assetId, target);
  let thumb: PreparedSealedUpload['thumb'];
  if (type === 'PHOTO') {
    try {
      const small = await manipulateAsync(uri, [{ resize: { width: 256 } }], { compress: 0.8, format: SaveFormat.JPEG });
      const plain = new File(small.uri);
      try {
        const ct = encryptMedia(key, 'thumb', memoryId, assetId, await plain.bytes());
        const file = dir ? new File(dir, `sealed-${assetId}.thumb.lkm`) : cacheFile(`${Crypto.randomUUID()}.lkm`);
        file.create();
        const out = file.open(FileMode.WriteOnly);
        try {
          await out.writeBytes(ct);
        } finally {
          out.close();
        }
        thumb = { fileUri: file.uri, width: small.width, height: small.height };
      } finally {
        if (plain.exists) plain.delete(); // the plaintext thumbnail never outlives this call
      }
    } catch {
      thumb = undefined; // optional: the tile shows a placeholder instead
    }
  }
  return { assetId, fileUri: encrypted.uri, thumb };
}

/**
 * Uploads a prepared sealed file (and its thumbnail), then deletes the local ciphertext. Safe to retry:
 * the server hands out a fresh URL for the same unfinished asset, and completing is idempotent.
 */
export async function sendSealedUpload(memoryId: string, type: AssetType, mimeType: string, prepared: PreparedSealedUpload): Promise<Asset> {
  const encrypted = new File(prepared.fileUri);
  let asset: Asset;
  try {
    const ticket = await api.initiateUpload(memoryId, type, mimeType, encrypted.size ?? 0, await sha256Base64(encrypted), prepared.assetId);
    await putFile(encrypted, ticket);
    asset = await api.completeUpload(ticket.assetId);
  } catch (e) {
    // Uploaded on an earlier try whose answer was lost: completing again returns the asset.
    if (!(e instanceof ApiError) || e.code !== 'ASSET_EXISTS') throw e;
    asset = await api.completeUpload(prepared.assetId);
  }
  if (asset.uploadStatus === 'VERIFICATION_FAILED') throw new Error('The upload did not match its checksum. Please try again.');
  if (prepared.thumb) await sendSealedThumbnail(prepared.assetId, prepared.thumb).catch(() => undefined); // optional
  discardPreparedUpload(prepared);
  return asset;
}

async function sendSealedThumbnail(assetId: string, thumb: NonNullable<PreparedSealedUpload['thumb']>) {
  const ct = await new File(thumb.fileUri).bytes();
  const ticket = await api.sealedThumbnail(assetId, ct.length, toBase64(sha256(ct)));
  const res = await fetch(ticket.uploadUrl, { method: 'PUT', headers: ticket.headers, body: ct.slice().buffer as ArrayBuffer });
  if (!res.ok) throw new Error(`Thumbnail upload failed (${res.status})`);
  await api.sealedThumbnailComplete(assetId, thumb.width, thumb.height);
}

/** Deletes a prepared upload's local ciphertext (sent, or the user gave up on it). */
export function discardPreparedUpload(prepared: PreparedSealedUpload) {
  for (const uri of [prepared.fileUri, prepared.thumb?.fileUri]) {
    if (!uri) continue;
    try {
      const f = new File(uri);
      if (f.exists) f.delete();
    } catch {
      // best effort
    }
  }
}

/** Online sealed upload (web, or when the queue isn't available): prepare in the cache, then send. */
export async function uploadSealedFile(
  vaultId: string,
  keyVersion: number,
  memoryId: string,
  uri: string,
  type: AssetType,
  mimeType: string,
): Promise<Asset> {
  const prepared = await prepareSealedUpload(vaultId, keyVersion, memoryId, uri, type);
  try {
    return await sendSealedUpload(memoryId, type, mimeType, prepared);
  } finally {
    discardPreparedUpload(prepared);
  }
}

/** Plaintext copies made for viewing, by asset and purpose, so a grid doesn't decrypt twice. */
const decrypted = new Map<string, string>();

export function extensionFor(mimeType: string): string {
  const map: Record<string, string> = {
    'image/jpeg': '.jpg', 'image/png': '.png', 'image/heic': '.heic', 'image/heif': '.heif', 'image/webp': '.webp',
    'video/mp4': '.mp4', 'video/quicktime': '.mov', 'video/webm': '.webm', 'audio/mpeg': '.mp3', 'audio/mp4': '.m4a',
    'audio/aac': '.aac', 'audio/ogg': '.ogg', 'audio/wav': '.wav', 'audio/webm': '.webm',
  };
  return map[mimeType] ?? '';
}

/** Downloads and decrypts a sealed asset (or its thumbnail) into the cache; returns a local file URI. */
export async function openSealedAsset(vaultId: string, keyVersion: number, memoryId: string, asset: Asset, thumb: boolean) {
  const cacheKey = `${asset.id}:${thumb ? 'thumb' : 'original'}`;
  const cached = decrypted.get(cacheKey);
  if (cached && new File(cached).exists) return cached;
  const { url, variant } = await api.downloadUrl(asset.id, thumb ? 'THUMB' : undefined);
  const purpose: MediaPurpose = variant === 'THUMB' ? 'thumb' : 'original';
  const encrypted = cacheFile(`${Crypto.randomUUID()}.lkm`);
  await File.downloadFileAsync(url, encrypted);
  try {
    const plain = await decryptFile(encrypted, vaultKey(vaultId, keyVersion), purpose, memoryId, asset.id,
      purpose === 'thumb' ? '.jpg' : extensionFor(asset.mimeType));
    decrypted.set(cacheKey, plain.uri);
    return plain.uri;
  } finally {
    if (encrypted.exists) encrypted.delete();
  }
}

/** Deletes one decrypted copy, e.g. when the player closes, so plaintext doesn't linger in the cache. */
export function evictDecrypted(assetId: string) {
  for (const purpose of ['original', 'thumb']) {
    const uri = decrypted.get(`${assetId}:${purpose}`);
    if (!uri) continue;
    decrypted.delete(`${assetId}:${purpose}`);
    try {
      const f = new File(uri);
      if (f.exists) f.delete();
    } catch {
      // best effort
    }
  }
}

/**
 * Deletes every plaintext or ciphertext temp file this module or the sealed export ever wrote to the
 * cache, including ones left by a crash or a killed app (review F4: the in-memory list alone forgets
 * them). Run at launch and at sign-out.
 */
export function sweepSealedCache() {
  try {
    for (const entry of new Directory(Paths.cache).list()) {
      if (entry instanceof File && (entry.name.startsWith('sealed-') || entry.name.startsWith('lock-export-'))) {
        try {
          entry.delete();
        } catch {
          // best effort
        }
      }
    }
  } catch {
    // no cache directory (web)
  }
}

/** Sign-out and memory-pressure cleanup: delete every decrypted copy. */
export function clearDecryptedMedia() {
  for (const uri of decrypted.values()) {
    try {
      const f = new File(uri);
      if (f.exists) f.delete();
    } catch {
      // best effort
    }
  }
  decrypted.clear();
  sweepSealedCache();
}

/**
 * For export: downloads a sealed original and hands its plaintext to `sink` one chunk (1 MiB) at a
 * time, so a file of any size passes through in bounded memory and no plaintext copy is written to
 * disk. `final` is true on the last chunk. Each chunk is authenticated before it is handed over; a
 * truncated or reordered file throws, and the caller must discard what it wrote.
 */
export async function streamSealedAsset(
  vaultId: string,
  keyVersion: number,
  memoryId: string,
  asset: Asset,
  sink: (plain: Uint8Array, final: boolean) => void,
): Promise<void> {
  const { url } = await api.downloadUrl(asset.id);
  const encrypted = cacheFile(`${Crypto.randomUUID()}.lkm`);
  await File.downloadFileAsync(url, encrypted);
  const input = encrypted.open(FileMode.ReadOnly);
  try {
    const dec = mediaDecryptor(vaultKey(vaultId, keyVersion), await input.readBytes(MEDIA_HEADER), 'original', memoryId, asset.id);
    const total = (encrypted.size ?? 0) - MEDIA_HEADER;
    let read = 0;
    do {
      const piece = await input.readBytes(MEDIA_CHUNK + TAG);
      if (piece.length === 0) throw new Error('The file ended early');
      read += piece.length;
      const final = read >= total;
      sink(dec.chunk(piece, final), final);
    } while (read < total);
    if (!dec.finished) throw new SealedError('This file is truncated');
  } finally {
    input.close();
    if (encrypted.exists) encrypted.delete();
  }
}
