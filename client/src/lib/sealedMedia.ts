/**
 * Media encryption for Sealed Vaults (roadmap 3.6d). Files are encrypted on the device in 1 MiB chunks
 * so large videos never sit in memory whole, and so a truncated or reordered file fails to decrypt.
 *
 *   header (16 bytes): "LKM1" | version 0x01 | file salt (11 random bytes)
 *   file key         : HKDF-SHA256(vault key, salt = file salt, info = "lock:media:v1:<purpose>:<memoryId>")
 *   chunk i          : AES-256-GCM(file key, nonce = 0^7 | uint32be(i) | last ? 1 : 0, aad = header) → ct + tag(16)
 *
 * A fresh salt gives every file its own key, so the counter nonces never repeat under one key. The
 * server only checks the length arithmetic (backend E2eFormats.mediaOverhead); keep both in step.
 */
import './cryptoRandom';
import { gcm } from '@noble/ciphers/aes';
import { hkdf } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha2';
import { randomBytes } from '@noble/hashes/utils';

import { SealedError } from './sealed';

export const MEDIA_CHUNK = 1 << 20;
export const MEDIA_HEADER = 16;
const TAG = 16;
const MAGIC = [0x4c, 0x4b, 0x4d, 0x31]; // "LKM1"
const encoder = new TextEncoder();

export type MediaPurpose = 'original' | 'thumb';

function fileKey(vaultKey: Uint8Array, header: Uint8Array, purpose: MediaPurpose, memoryId: string): Uint8Array {
  return hkdf(sha256, vaultKey, header.subarray(5, 16), encoder.encode(`lock:media:v1:${purpose}:${memoryId}`), 32);
}

function nonce(index: number, last: boolean): Uint8Array {
  const n = new Uint8Array(12);
  new DataView(n.buffer).setUint32(7, index, false);
  n[11] = last ? 1 : 0;
  return n;
}

/** Size of the ciphertext for a plaintext of `plainBytes` (the server applies limits to the plaintext). */
export function encryptedSize(plainBytes: number, chunk = MEDIA_CHUNK): number {
  const chunks = Math.max(1, Math.ceil(plainBytes / chunk));
  return MEDIA_HEADER + plainBytes + chunks * TAG;
}

/** Streaming encryptor: call `chunk()` for each piece of plaintext (`last` on the final one). */
export function mediaEncryptor(vaultKey: Uint8Array, purpose: MediaPurpose, memoryId: string) {
  const header = new Uint8Array(MEDIA_HEADER);
  header.set(MAGIC, 0);
  header[4] = 1;
  header.set(randomBytes(11), 5);
  const key = fileKey(vaultKey, header, purpose, memoryId);
  let index = 0;
  let done = false;
  return {
    header,
    chunk(plain: Uint8Array, last: boolean): Uint8Array {
      if (done) throw new SealedError('Encryption already finished');
      done = last;
      return gcm(key, nonce(index++, last), header).encrypt(plain);
    },
  };
}

/** Streaming decryptor over ciphertext chunks of `chunk + 16` bytes (the last may be shorter). */
export function mediaDecryptor(vaultKey: Uint8Array, header: Uint8Array, purpose: MediaPurpose, memoryId: string) {
  if (header.length !== MEDIA_HEADER || MAGIC.some((b, i) => header[i] !== b) || header[4] !== 1) {
    throw new SealedError('Not an encrypted Lock media file');
  }
  const key = fileKey(vaultKey, header, purpose, memoryId);
  let index = 0;
  let done = false;
  return {
    chunk(ciphertext: Uint8Array, last: boolean): Uint8Array {
      if (done) throw new SealedError('Data after the end of the file');
      done = last;
      try {
        return gcm(key, nonce(index++, last), header).decrypt(ciphertext);
      } catch {
        throw new SealedError('This file is damaged or was not encrypted for this vault');
      }
    },
    get finished() {
      return done;
    },
  };
}

/** Whole-buffer helpers, for thumbnails and tests. */
export function encryptMedia(vaultKey: Uint8Array, purpose: MediaPurpose, memoryId: string, plain: Uint8Array, chunk = MEDIA_CHUNK) {
  const enc = mediaEncryptor(vaultKey, purpose, memoryId);
  const parts: Uint8Array[] = [enc.header];
  const count = Math.max(1, Math.ceil(plain.length / chunk));
  for (let i = 0; i < count; i++) parts.push(enc.chunk(plain.subarray(i * chunk, (i + 1) * chunk), i === count - 1));
  return concat(parts);
}

export function decryptMedia(vaultKey: Uint8Array, purpose: MediaPurpose, memoryId: string, data: Uint8Array, chunk = MEDIA_CHUNK) {
  const dec = mediaDecryptor(vaultKey, data.subarray(0, MEDIA_HEADER), purpose, memoryId);
  const parts: Uint8Array[] = [];
  const step = chunk + TAG;
  for (let offset = MEDIA_HEADER; offset < data.length; offset += step) {
    parts.push(dec.chunk(data.subarray(offset, offset + step), offset + step >= data.length));
  }
  if (!dec.finished) throw new SealedError('This file is truncated');
  return concat(parts);
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}
