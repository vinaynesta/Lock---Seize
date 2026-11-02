/**
 * Native file plumbing for sealed media (roadmap 3.6d/f): stream-encrypt a picked file into the cache,
 * upload it with the native uploader, and download + decrypt into the cache for viewing. Plaintext
 * copies live only in the app's cache directory and are deleted when no longer needed.
 */
import './cryptoRandom';
import { sha256 } from '@noble/hashes/sha2';
import * as Crypto from 'expo-crypto';
import { File, FileMode, Paths } from 'expo-file-system';
import { manipulateAsync, SaveFormat } from 'expo-image-manipulator';

import { api } from '@/api/lock';
import type { Asset, AssetType } from '@/api/types';

import { toBase64 } from './sealed';
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
      const piece = handle.readBytes(MEDIA_CHUNK);
      if (piece.length === 0) break;
      h.update(piece);
    }
  } finally {
    handle.close();
  }
  return toBase64(h.digest());
}

/** Encrypts `source` chunk by chunk into a new cache file. Never holds the whole file in memory. */
function encryptFile(source: File, key: Uint8Array, purpose: MediaPurpose, memoryId: string): File {
  const target = cacheFile(`${Crypto.randomUUID()}.lkm`);
  target.create();
  const enc = mediaEncryptor(key, purpose, memoryId);
  const input = source.open(FileMode.ReadOnly);
  const output = target.open(FileMode.WriteOnly);
  try {
    output.writeBytes(enc.header);
    const total = source.size ?? 0;
    let read = 0;
    do {
      const piece = input.readBytes(MEDIA_CHUNK);
      read += piece.length;
      output.writeBytes(enc.chunk(piece, read >= total));
    } while (read < total);
  } finally {
    input.close();
    output.close();
  }
  return target;
}

/** Decrypts an encrypted cache file into a plaintext cache file, chunk by chunk. */
function decryptFile(source: File, key: Uint8Array, purpose: MediaPurpose, memoryId: string, extension: string): File {
  const target = cacheFile(`${Crypto.randomUUID()}${extension}`);
  target.create();
  const input = source.open(FileMode.ReadOnly);
  const output = target.open(FileMode.WriteOnly);
  try {
    const dec = mediaDecryptor(key, input.readBytes(MEDIA_HEADER), purpose, memoryId);
    const total = (source.size ?? 0) - MEDIA_HEADER;
    let read = 0;
    while (read < total) {
      const piece = input.readBytes(MEDIA_CHUNK + TAG);
      read += piece.length;
      output.writeBytes(dec.chunk(piece, read >= total));
    }
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

/**
 * Sealed upload: encrypt the original on the device, upload the ciphertext, then (photos) make a 256 px
 * thumbnail, encrypt it and upload that too. The server never sees a plaintext byte.
 */
export async function uploadSealedFile(
  vaultId: string,
  keyVersion: number,
  memoryId: string,
  uri: string,
  type: AssetType,
  mimeType: string,
): Promise<Asset> {
  const key = vaultKey(vaultId, keyVersion);
  const source = new File(uri);
  const encrypted = encryptFile(source, key, 'original', memoryId);
  try {
    const ticket = await api.initiateUpload(memoryId, type, mimeType, encrypted.size ?? 0, await sha256Base64(encrypted));
    await putFile(encrypted, ticket);
    const asset = await api.completeUpload(ticket.assetId);
    if (asset.uploadStatus === 'VERIFICATION_FAILED') throw new Error('The upload did not match its checksum. Please try again.');
    if (type === 'PHOTO') await uploadSealedThumbnail(key, memoryId, ticket.assetId, uri).catch(() => undefined); // optional
    return asset;
  } finally {
    if (encrypted.exists) encrypted.delete();
  }
}

async function uploadSealedThumbnail(key: Uint8Array, memoryId: string, assetId: string, uri: string) {
  const thumb = await manipulateAsync(uri, [{ resize: { width: 256 } }], { compress: 0.8, format: SaveFormat.JPEG });
  const plain = new File(thumb.uri);
  try {
    const ct = encryptMedia(key, 'thumb', memoryId, await plain.bytes());
    const ticket = await api.sealedThumbnail(assetId, ct.length, toBase64(sha256(ct)));
    const res = await fetch(ticket.uploadUrl, { method: 'PUT', headers: ticket.headers, body: ct.slice().buffer as ArrayBuffer });
    if (!res.ok) throw new Error(`Thumbnail upload failed (${res.status})`);
    await api.sealedThumbnailComplete(assetId, thumb.width, thumb.height);
  } finally {
    if (plain.exists) plain.delete();
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
    const plain = decryptFile(encrypted, vaultKey(vaultId, keyVersion), purpose, memoryId,
      purpose === 'thumb' ? '.jpg' : extensionFor(asset.mimeType));
    decrypted.set(cacheKey, plain.uri);
    return plain.uri;
  } finally {
    if (encrypted.exists) encrypted.delete();
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
}

/** For export: the decrypted original as bytes (export streams files one at a time). */
export async function sealedAssetBytes(vaultId: string, keyVersion: number, memoryId: string, asset: Asset): Promise<Uint8Array> {
  const uri = await openSealedAsset(vaultId, keyVersion, memoryId, asset, false);
  return new File(uri).bytes();
}
