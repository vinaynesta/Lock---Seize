import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { MemoryInput } from '@/api/types';
import { MemoryForm } from '@/components/MemoryForm';
import { SealedGate } from '@/components/SealedGate';
import { Body, Screen } from '@/components/ui';
import { newIdempotencyKey } from '@/lib/format';
import { offlineSupported, queueDraft } from '@/lib/offlineQueue';
import { createSealedMemory } from '@/lib/sealedVault';
import { useSealedVault } from '@/lib/useSealed';

export default function Compose() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  // One key per compose session, so a retried submit after a timeout cannot create a duplicate.
  const [idempotencyKey] = useState(newIdempotencyKey);
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  const sealed = useSealedVault(vault.data);

  const create = useMutation({
    mutationFn: async (input: MemoryInput) => {
      if (sealed.status === 'ready') {
        // Encrypted here; never queued offline in plaintext. (Offline sealed drafts: a later 3.6 piece.)
        return { memory: await createSealedMemory(vault.data!, sealed.keyVersion, input, idempotencyKey), queued: undefined };
      }
      try {
        return { memory: await api.createMemory(id, input, idempotencyKey), queued: undefined };
      } catch (e) {
        // No answer at all (offline, not an HTTP error): keep the draft on this device and sync later.
        if (offlineSupported && !(e instanceof ApiError)) return { memory: undefined, queued: await queueDraft(id, input) };
        throw e;
      }
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['timeline', id] });
      if (result.memory) router.replace({ pathname: '/memory/[id]', params: { id: result.memory.id } });
      else router.back();
    },
  });

  if (sealed.status !== 'server' && sealed.status !== 'ready') {
    return (
      <Screen>
        <SealedGate state={sealed} />
      </Screen>
    );
  }

  return (
    <Screen>
      {sealed.status === 'ready' ? <Body>🔒 Encrypted on this device before it is sent.</Body> : null}
      <Body muted>
        This starts as a draft. You can add photos and edit it until you lock it. Offline? It is saved on this device and
        synced when you are back online.
      </Body>
      <MemoryForm submitLabel="Save draft" onSubmit={(input) => create.mutate(input)} busy={create.isPending} error={create.error} />
    </Screen>
  );
}
