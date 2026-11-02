import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Crypto from 'expo-crypto';
import { Directory, File, Paths } from 'expo-file-system';
import * as Network from 'expo-network';
import { Platform } from 'react-native';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { AssetType, MemoryInput } from '@/api/types';
import { newIdempotencyKey } from '@/lib/format';

/**
 * Offline drafts and background upload (roadmap 3.5).
 *
 * A draft captured offline, and every photo or video added to a draft, goes into a queue persisted on
 * the device first. Media files are copied into the app's documents directory, so they survive the
 * picker's temporary files being cleaned up and the app being killed. The queue is worked through in
 * order whenever the network is back and the app is open:
 *
 *   createDraft → POST /vaults/{id}/memories with a fixed Idempotency-Key, so a retry after a lost
 *                 response returns the same draft instead of creating a second one;
 *   upload      → hash the file, presign, PUT it straight to storage with the native uploader
 *                 (streamed from disk), then complete.
 *
 * An upload that waits for a draft created offline refers to it by its local id; once the draft
 * exists on the server the local id maps to the real one.
 *
 * Native only: the web build has no durable file storage, and uploads there go straight through.
 */

const KEY = 'lock.offline-queue.v1';
const MAX_ATTEMPTS = 8;

export type QueueItem =
  | {
      kind: 'createDraft';
      localId: string;
      vaultId: string;
      input: MemoryInput;
      idempotencyKey: string;
      attempts: number;
      lastError?: string;
    }
  | {
      kind: 'upload';
      localId: string;
      /** A server memory id, or the localId of a queued createDraft. */
      memoryRef: string;
      fileUri: string;
      type: AssetType;
      mimeType: string;
      attempts: number;
      lastError?: string;
    };

type Listener = (items: QueueItem[]) => void;

let items: QueueItem[] | null = null;
let idMap: Record<string, string> = {};
const listeners = new Set<Listener>();
let draining: Promise<void> | null = null;

export const offlineSupported = Platform.OS !== 'web';

async function load(): Promise<QueueItem[]> {
  if (items) return items;
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as { items: QueueItem[]; idMap: Record<string, string> }) : null;
    items = parsed?.items ?? [];
    idMap = parsed?.idMap ?? {};
  } catch {
    items = [];
  }
  return items;
}

async function save(next: QueueItem[]) {
  items = next;
  await AsyncStorage.setItem(KEY, JSON.stringify({ items: next, idMap }));
  listeners.forEach((l) => l(next));
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  void load().then(listener);
  return () => listeners.delete(listener);
}

function mediaDir(): Directory {
  const dir = new Directory(Paths.document, 'pending-uploads');
  if (!dir.exists) dir.create({ intermediates: true });
  return dir;
}

/** Saves a draft on the device and returns its local id. It is created on the server when online. */
export async function queueDraft(vaultId: string, input: MemoryInput): Promise<string> {
  const localId = `local-${Crypto.randomUUID()}`;
  const current = await load();
  await save([...current, { kind: 'createDraft', localId, vaultId, input, idempotencyKey: newIdempotencyKey(), attempts: 0 }]);
  void drain();
  return localId;
}

/** Copies a picked file into app storage and queues its upload to a draft (server id or local id). */
export async function queueUpload(memoryRef: string, uri: string, type: AssetType, mimeType: string): Promise<void> {
  const localId = Crypto.randomUUID();
  const source = new File(uri);
  const extension = uri.split('?')[0].split('.').pop() ?? 'bin';
  const target = new File(mediaDir(), `${localId}.${extension.length <= 5 ? extension : 'bin'}`);
  source.copySync(target);
  const current = await load();
  await save([...current, { kind: 'upload', localId, memoryRef, fileUri: target.uri, type, mimeType, attempts: 0 }]);
  void drain();
}

/** Pending items for one draft (by server id or local id), for showing "waiting to upload" tiles. */
export function pendingFor(all: QueueItem[], memoryId: string): QueueItem[] {
  return all.filter((i) => i.kind === 'upload' && (i.memoryRef === memoryId || idMap[i.memoryRef] === memoryId));
}

/** The server id a locally created draft now has, once it has synced. */
export function serverIdOf(localId: string): string | undefined {
  return idMap[localId];
}

/**
 * Works through the queue in order. Single-flight; safe to call any time. A transient failure
 * (offline, 5xx, 429) stops the run and leaves the item first, to retry later. A permanent one (e.g.
 * QUOTA_EXCEEDED) or too many attempts parks the item as failed, visible to the user, so it never
 * blocks what comes after it.
 */
export function drain(): Promise<void> {
  draining ??= (async () => {
    try {
      const state = await Network.getNetworkStateAsync();
      if (state.isConnected === false || state.isInternetReachable === false) return;
      for (;;) {
        const queue = await load();
        const next = queue.find((i) => !isFailed(i));
        if (!next) return;
        try {
          await process(next);
          await save((await load()).filter((i) => i.localId !== next.localId));
        } catch (e) {
          const message = e instanceof Error ? e.message : String(e);
          const permanent = e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429;
          const attempts = permanent ? MAX_ATTEMPTS : next.attempts + 1;
          await save((await load()).map((i) => (i.localId === next.localId ? { ...i, attempts, lastError: message } : i)));
          if (attempts < MAX_ATTEMPTS) return; // probably offline again; try later
        }
      }
    } finally {
      draining = null;
    }
  })();
  return draining;
}

export function isFailed(item: QueueItem): boolean {
  return item.attempts >= MAX_ATTEMPTS;
}

async function process(item: QueueItem) {
  if (item.kind === 'createDraft') {
    const memory = await api.createMemory(item.vaultId, item.input, item.idempotencyKey);
    idMap = { ...idMap, [item.localId]: memory.id };
    return;
  }
  const memoryId = item.memoryRef.startsWith('local-') ? idMap[item.memoryRef] : item.memoryRef;
  if (!memoryId) throw new Error('Waiting for the draft to sync');
  const file = new File(item.fileUri);
  if (!file.exists) return; // removed from the device; nothing to upload
  const bytes = await file.bytes();
  const checksum = toBase64(await Crypto.digest(Crypto.CryptoDigestAlgorithm.SHA256, bytes));
  const ticket = await api.initiateUpload(memoryId, item.type, item.mimeType, file.size, checksum);
  // The native uploader streams from disk and survives the app being briefly backgrounded.
  const result = await file.upload(ticket.uploadUrl, { httpMethod: 'PUT', headers: ticket.headers });
  if (result.status < 200 || result.status >= 300) throw new Error(`Upload to storage failed (${result.status})`);
  await api.completeUpload(ticket.assetId);
  file.delete();
}

/** Drops a failed item (the user chose to give up on it). */
export async function discard(localId: string) {
  const current = await load();
  const item = current.find((i) => i.localId === localId);
  if (item?.kind === 'upload') {
    const f = new File(item.fileUri);
    if (f.exists) f.delete();
  }
  await save(current.filter((i) => i.localId !== localId));
}

/** Retries failed items. */
export async function retryFailed() {
  const current = await load();
  await save(current.map((i) => ({ ...i, attempts: 0, lastError: undefined })));
  await drain();
}

/** Called once at startup: drain now, and whenever connectivity comes back. */
export function startOfflineSync(): () => void {
  if (!offlineSupported) return () => undefined;
  void drain();
  const sub = Network.addNetworkStateListener((s) => {
    if (s.isConnected) void drain();
  });
  return () => sub.remove();
}

function toBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}
