import { useMutation, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { api } from '@/api/lock';
import type { MemoryInput } from '@/api/types';
import { MemoryForm } from '@/components/MemoryForm';
import { Body, Screen } from '@/components/ui';
import { newIdempotencyKey } from '@/lib/format';

export default function Compose() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  // One key per compose session, so a retried submit after a timeout cannot create a duplicate.
  const [idempotencyKey] = useState(newIdempotencyKey);

  const create = useMutation({
    mutationFn: (input: MemoryInput) => api.createMemory(id, input, idempotencyKey),
    onSuccess: (memory) => {
      queryClient.invalidateQueries({ queryKey: ['timeline', id] });
      router.replace({ pathname: '/memory/[id]', params: { id: memory.id } });
    },
  });

  return (
    <Screen>
      <Body muted>This starts as a draft. You can add photos and edit it until you lock it.</Body>
      <MemoryForm submitLabel="Save draft" onSubmit={(input) => create.mutate(input)} busy={create.isPending} error={create.error} />
    </Screen>
  );
}
