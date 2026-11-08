import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';

import { api } from '@/api/lock';
import type { MemoryInput } from '@/api/types';
import { MemoryForm } from '@/components/MemoryForm';
import { SealedGate } from '@/components/SealedGate';
import { updateSealedDraft } from '@/lib/sealedVault';
import { useSealedVault } from '@/lib/useSealed';
import { ErrorText, Loading, Screen } from '@/components/ui';

export default function EditDraft() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  const memory = useQuery({ queryKey: ['memory', id], queryFn: () => api.memory(id) });
  const vault = useQuery({
    queryKey: ['vault', memory.data?.vaultId],
    queryFn: () => api.vault(memory.data!.vaultId),
    enabled: !!memory.data,
  });
  const sealed = useSealedVault(vault.data);
  const save = useMutation({
    mutationFn: (input: MemoryInput) =>
      sealed.status === 'ready' ? updateSealedDraft(memory.data!, sealed.keyVersion, input) : api.updateDraft(id, input),
    onSuccess: (m) => {
      queryClient.setQueryData(['memory', id], m);
      queryClient.invalidateQueries({ queryKey: ['timeline', m.vaultId] });
      router.back();
    },
  });

  if (memory.isPending) return <Loading />;
  if (memory.error) {
    return (
      <Screen>
        <ErrorText error={memory.error} />
      </Screen>
    );
  }

  const m = memory.data;
  if (m.encrypted && (sealed.status !== 'ready' || m.title === null)) {
    return (
      <Screen>
        <SealedGate state={sealed.status === 'ready' ? { status: 'loading' } : sealed} />
      </Screen>
    );
  }
  return (
    <Screen>
      <MemoryForm
        initial={{
          title: m.title ?? '',
          thought: m.thought,
          feeling: m.feeling,
          location: m.location,
          coordinates: m.coordinates ?? null,
          occurredAt: m.occurredAt,
          revealAt: m.revealAt,
        }}
        submitLabel="Save"
        onSubmit={(input) => save.mutate(input)}
        busy={save.isPending}
        error={save.error}
      />
    </Screen>
  );
}
