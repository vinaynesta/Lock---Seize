import { api } from '@/api/lock';
import type { Memory, Vault } from '@/api/types';

import { deviceUnlocked, openVault, restoreDeviceKeys } from './sealedVault';

/** Enough for a lifetime of memories in one vault; maps load them all to place every pin. */
const MAX_MEMORIES = 2000;

/** Every memory of one vault, page by page (sealed content decrypts in the API layer once keys are open). */
export async function allMemories(vaultId: string): Promise<Memory[]> {
  const out: Memory[] = [];
  let cursor: string | undefined;
  do {
    const page = await api.timeline(vaultId, { limit: 100, cursor });
    out.push(...page.memories);
    cursor = page.nextCursor ?? undefined;
  } while (cursor && out.length < MAX_MEMORIES);
  return out;
}

export interface AllVaultsMap {
  memories: Memory[];
  vaultNames: Record<string, string>;
  /** Sealed vaults this device can't read right now (keys locked or not set up). */
  skipped: Vault[];
}

/**
 * Memories of every vault for the all-memories map (roadmap 5.2). Sealed vaults are included only if
 * this device can open them without asking (keys unlocked or restorable from the Keychain); the rest
 * are listed as skipped, never decrypted on the server.
 */
export async function allVaultsMap(): Promise<AllVaultsMap> {
  const [vaults, me] = await Promise.all([api.vaults(), api.me()]);
  let bundlePublicKey: string | null = null;
  if (vaults.some((v) => v.encryption === 'SEALED')) {
    bundlePublicKey = await api.keyBundle().then((b) => b.publicKey, () => null);
  }
  const memories: Memory[] = [];
  const vaultNames: Record<string, string> = {};
  const skipped: Vault[] = [];
  for (const v of vaults) {
    vaultNames[v.id] = v.name;
    if (v.encryption === 'SEALED') {
      const unlocked = deviceUnlocked() || (bundlePublicKey !== null && (await restoreDeviceKeys(bundlePublicKey)));
      if (!unlocked) {
        skipped.push(v);
        continue;
      }
      try {
        await openVault(v, me.id);
      } catch {
        skipped.push(v);
        continue;
      }
    }
    memories.push(...(await allMemories(v.id)));
  }
  return { memories, vaultNames, skipped };
}
