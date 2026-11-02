/**
 * Device-to-device approval for Sealed Vault keys (roadmap 3.6e, decisions §24 option B).
 *
 *   new device                    server                       unlocked device
 *   one-time X25519 pair  ──POST requestKey──▶  stores   ◀──GET pending── lists requests
 *   QR: lock-approve:v1:<id>:<fingerprint>   ─────────────────────────▶  scans QR, compares the
 *                                                                      fingerprint with the request
 *                         ◀──POST sealed secret──  wraps the account secret key to the one-time key
 *   POST collect ─▶ sealed secret (once) ─▶ unwrap, check against the account public key
 *
 * The fingerprint check on the approving device is what stops a server, or someone holding a stolen
 * token, from slipping in its own request key: it would not match the QR shown on the user's device.
 */
import './cryptoRandom';
import { x25519 } from '@noble/curves/ed25519';

import { api } from '@/api/lock';

import { fingerprint, fromBase64, generateKeyPair, SealedError, toBase64, unwrapVaultKey, wrapVaultKey, type KeyPair } from './sealed';

const QR_PREFIX = 'lock-approve:v1:';
/** Domain-separates this wrap from vault-key wraps, which use the same sealed-box construction. */
const PURPOSE = 'device-approval';

export interface PendingRequest {
  id: string;
  pair: KeyPair;
  qr: string;
  fingerprint: string;
}

/** New device: start a request; show `qr` and keep `pair` in memory until collected. */
export async function startRequest(deviceName?: string): Promise<PendingRequest> {
  const pair = generateKeyPair();
  const created = await api.requestDeviceApproval(toBase64(pair.publicKey), deviceName);
  const fp = fingerprint(pair.publicKey);
  if (fp !== created.fingerprint) throw new SealedError('The server changed the request key');
  return { id: created.id, pair, qr: `${QR_PREFIX}${created.id}:${fp.replace(/ /g, '')}`, fingerprint: fp };
}

export function parseQr(data: string): { id: string; fingerprint: string } | null {
  if (!data.startsWith(QR_PREFIX)) return null;
  const [id, fp] = data.slice(QR_PREFIX.length).split(':');
  if (!id || !/^[0-9a-f]{32}$/.test(fp ?? '')) return null;
  return { id, fingerprint: fp.match(/.{4}/g)!.join(' ') };
}

/**
 * Unlocked device: approve the request the QR names, only if the server's copy of the request key
 * has exactly the fingerprint the QR showed.
 */
export async function approve(qrData: string, accountSecretKey: Uint8Array, userId: string) {
  const parsed = parseQr(qrData);
  if (!parsed) throw new SealedError('That is not a Lock device approval code');
  const request = (await api.deviceApprovals()).find((r) => r.id === parsed.id);
  if (!request) throw new SealedError('That request has expired or belongs to another account');
  const requestKey = fromBase64(request.requestKey);
  if (fingerprint(requestKey) !== parsed.fingerprint) {
    throw new SealedError('The key the server returned does not match the code on the other device. Nothing was shared.');
  }
  const sealed = wrapVaultKey(accountSecretKey, requestKey, PURPOSE, 1, `${userId}:${request.id}`);
  await api.approveDevice(request.id, toBase64(sealed));
  return request;
}

/**
 * New device: poll until approved. Returns the account key pair, checked against the account's public
 * key so a wrong or substituted secret is never adopted.
 */
export async function collect(pending: PendingRequest, userId: string, accountPublicKey: string): Promise<KeyPair | null> {
  const sealed = await api.collectDeviceApproval(pending.id);
  if (!sealed) return null;
  const secretKey = unwrapVaultKey(fromBase64(sealed), pending.pair, PURPOSE, 1, `${userId}:${pending.id}`);
  const publicKey = x25519.getPublicKey(secretKey);
  if (toBase64(publicKey) !== accountPublicKey) throw new SealedError('The approving device sent keys of another account');
  return { secretKey, publicKey };
}
