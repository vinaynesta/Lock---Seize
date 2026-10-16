import * as Crypto from 'expo-crypto';
import type { ImagePickerAsset } from 'expo-image-picker';

import { api } from './lock';
import type { Asset, AssetType } from './types';

function toBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function assetTypeOf(picked: ImagePickerAsset): AssetType {
  return picked.type === 'video' ? 'VIDEO' : 'PHOTO';
}

function mimeTypeOf(picked: ImagePickerAsset): string {
  if (picked.mimeType) return picked.mimeType;
  return picked.type === 'video' ? 'video/mp4' : 'image/jpeg';
}

/**
 * Direct-to-storage upload (design doc §11):
 *   1. hash the bytes on device,
 *   2. ask the API for a presigned PUT (the SHA-256 is signed into it),
 *   3. PUT the bytes straight to object storage,
 *   4. ask the API to verify size + checksum.
 *
 * Reads the whole file into memory, which is fine for photos. Large videos should move to a
 * streaming/multipart upload later.
 */
export async function uploadPickedAsset(memoryId: string, picked: ImagePickerAsset): Promise<Asset> {
  const bytes = await (await fetch(picked.uri)).arrayBuffer();
  const checksum = toBase64(await Crypto.digest(Crypto.CryptoDigestAlgorithm.SHA256, new Uint8Array(bytes)));
  const mimeType = mimeTypeOf(picked);

  const ticket = await api.initiateUpload(memoryId, assetTypeOf(picked), mimeType, bytes.byteLength, checksum);

  const put = await fetch(ticket.uploadUrl, { method: ticket.method, headers: ticket.headers, body: bytes });
  if (!put.ok) {
    throw new Error(`Upload to storage failed (${put.status})`);
  }

  const asset = await api.completeUpload(ticket.assetId);
  if (asset.uploadStatus === 'VERIFICATION_FAILED') {
    throw new Error('The uploaded file did not match its checksum. Please try again.');
  }
  return asset;
}
