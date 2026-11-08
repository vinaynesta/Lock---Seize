/**
 * On-device export of a sealed vault (roadmap 3.6f). The server can't build it (it has no key), so the
 * device pages through the timeline, decrypts everything and writes the same ZIP layout as the server
 * export (format lock-export/v2: manifest.json + media/<memoryId>/<assetId><ext>). The ZIP holds
 * plaintext: it is written to the cache, handed to the share sheet, and deleted afterwards (also when
 * the export fails part-way). Media is streamed chunk by chunk from download to ZIP, so a single file
 * larger than the device's free memory still exports.
 */
import { File, FileMode, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { Zip, ZipPassThrough, strToU8 } from 'fflate';

import { api } from '@/api/lock';
import type { Memory, Vault } from '@/api/types';

import { decryptReflection } from './sealedDecrypt';
import { extensionFor, streamSealedAsset } from './sealedFiles';

const FORMAT = 'lock-export/v2';

export interface ExportProgress {
  memories: number;
  files: number;
}

export async function exportSealedVault(vault: Vault, onProgress: (p: ExportProgress) => void): Promise<void> {
  const target = new File(Paths.cache, `lock-export-${vault.id}.zip`);
  if (target.exists) target.delete();
  target.create();
  const out = target.open(FileMode.WriteOnly);
  let failure: unknown = null;
  const zip = new Zip((err, chunk) => {
    if (err) failure = err;
    else out.writeBytesSync(chunk); // fflate calls back synchronously; keep chunks in order
  });
  const add = (path: string, bytes: Uint8Array) => {
    const entry = new ZipPassThrough(path); // the manifest is small
    zip.add(entry);
    entry.push(bytes, true);
  };

  const manifest: unknown[] = [];
  let files = 0;
  try {
    let cursor: string | undefined;
    do {
      const page = await api.timeline(vault.id, { limit: 50, cursor });
      for (const summary of page.memories) {
        const m: Memory = await api.memory(summary.id);
        if (m.title === null && m.encrypted) throw new Error("This device doesn't have every key for this vault yet");
        const reflections =
          (m.state === 'LOCKED' || m.state === 'ARCHIVED') && !m.sealedCapsule
            ? (await api.reflections(m.id)).map((r) => decryptReflection(vault.id, m.id, r))
            : [];
        const exported = [];
        for (const a of m.assets) {
          if (a.uploadStatus !== 'VERIFIED' && a.uploadStatus !== 'ATTACHED') continue;
          const path = `media/${m.id}/${a.id}${extensionFor(a.mimeType)}`;
          const entry = new ZipPassThrough(path); // media is already compressed
          zip.add(entry);
          await streamSealedAsset(vault.id, m.encrypted?.keyVersion ?? vault.keyVersion, m.id, a, (bytes, final) =>
            entry.push(bytes, final),
          );
          if (failure) throw failure;
          exported.push({ id: a.id, type: a.type, mimeType: a.mimeType, path });
          onProgress({ memories: manifest.length, files: ++files });
        }
        const { encrypted: _ciphertext, ...plain } = m; // the export is the readable copy
        manifest.push({ memory: plain, reflections, files: exported });
        onProgress({ memories: manifest.length, files });
      }
      cursor = page.nextCursor;
    } while (cursor);

    add('manifest.json', strToU8(JSON.stringify({ format: FORMAT, vaultId: vault.id, exportedAt: new Date().toISOString(),
      sealed: true, memories: manifest })));
    zip.end();
    if (failure) throw failure;
  } catch (e) {
    out.close();
    if (target.exists) target.delete(); // a partial export is still plaintext
    throw e;
  }
  out.close();
  try {
    await Sharing.shareAsync(target.uri, { mimeType: 'application/zip', dialogTitle: `Export of ${vault.name}` });
  } finally {
    if (target.exists) target.delete(); // plaintext: never left behind
  }
}
