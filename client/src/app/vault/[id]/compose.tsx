import { useMutation, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { MemoryInput } from '@/api/types';
import { MemoryForm } from '@/components/MemoryForm';
import { Body, Screen } from '@/components/ui';
import { newIdempotencyKey } from '@/lib/format';
import { offlineSupported, queueDraft } from '@/lib/offlineQueue';

export default function Compose() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  // One key per compose session, so a retried submit after a timeout cannot create a duplicate.
  const [idempotencyKey] = useState(newIdempotencyKey);

  const create = useMutation({
    mutationFn: async (input: MemoryInput) => {
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

  return (
    <Screen>
      <Body muted>
        This starts as a draft. You can add photos and edit it until you lock it. Offline? It is saved on this device and
        synced when you are back online.
      </Body>
      <MemoryForm submitLabel="Save draft" onSubmit={(input) => create.mutate(input)} busy={create.isPending} error={create.error} />
    </Screen>
  );
}
