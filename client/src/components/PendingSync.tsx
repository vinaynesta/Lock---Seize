import { useEffect, useState } from 'react';
import { View } from 'react-native';

import { discard, isFailed, offlineSupported, retryFailed, subscribe, type QueueItem } from '@/lib/offlineQueue';
import { space, useTheme } from '@/theme';

import { Body, Button, Card } from './ui';

/** Items saved on this device and not yet on the server (roadmap 3.5). Optionally only one vault's. */
export function usePendingSync(): QueueItem[] {
  const [items, setItems] = useState<QueueItem[]>([]);
  useEffect(() => (offlineSupported ? subscribe(setItems) : undefined), []);
  return items;
}

export function PendingSync({ vaultId }: { vaultId?: string }) {
  const t = useTheme();
  const all = usePendingSync();
  const items = vaultId ? all.filter((i) => i.kind === 'upload' || i.vaultId === vaultId) : all;
  if (items.length === 0) return null;
  const drafts = items.filter((i) => i.kind === 'createDraft' && !isFailed(i)).length;
  const uploads = items.filter((i) => i.kind === 'upload' && !isFailed(i)).length;
  const failed = items.filter(isFailed);
  return (
    <Card>
      {drafts + uploads > 0 ? (
        <Body muted>
          Waiting to sync: {[drafts ? `${drafts} draft${drafts === 1 ? '' : 's'}` : '', uploads ? `${uploads} file${uploads === 1 ? '' : 's'}` : '']
            .filter(Boolean)
            .join(' and ')}
          . They upload when you are online.
        </Body>
      ) : null}
      {failed.map((f) => (
        <View key={f.localId} style={{ gap: space.xs }}>
          <Body style={{ color: t.danger, fontSize: 14 }}>
            {f.kind === 'createDraft' ? `Draft “${f.input.title}”` : 'A file'} couldn’t be synced: {f.lastError}
          </Body>
          <Button title="Remove from this device" variant="secondary" onPress={() => void discard(f.localId)} />
        </View>
      ))}
      {failed.length > 0 ? <Button title="Try again" onPress={() => void retryFailed()} /> : null}
    </Card>
  );
}
