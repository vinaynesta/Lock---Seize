import { useQuery } from '@tanstack/react-query';
import { Stack, useLocalSearchParams } from 'expo-router';

import { api } from '@/api/lock';
import { MemoriesMapView } from '@/components/MemoriesMapView';
import { SealedGate } from '@/components/SealedGate';
import { ErrorText, Loading, Screen } from '@/components/ui';
import { allMemories } from '@/lib/mapData';
import { useSealedVault } from '@/lib/useSealed';

/** One vault's memories on a map (roadmap 5.1). */
export default function VaultMap() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  const sealed = useSealedVault(vault.data);
  const readable = sealed.status === 'server' || sealed.status === 'ready';
  const memories = useQuery({ queryKey: ['map', id, sealed.status], queryFn: () => allMemories(id), enabled: readable });

  return (
    <Screen>
      <Stack.Screen options={{ title: vault.data ? `${vault.data.name} · Map` : 'Map' }} />
      <SealedGate state={sealed} />
      {memories.isPending && readable ? <Loading /> : null}
      <ErrorText error={memories.error} />
      {memories.data ? <MemoriesMapView memories={memories.data} /> : null}
    </Screen>
  );
}
