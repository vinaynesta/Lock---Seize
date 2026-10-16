import { useMutation, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';

import { api } from '@/api/lock';
import { Body, Button, ErrorText, Field, Screen } from '@/components/ui';

export default function NewVault() {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const create = useMutation({
    mutationFn: () => api.createVault('SHARED', name.trim()),
    onSuccess: (vault) => {
      queryClient.invalidateQueries({ queryKey: ['vaults'] });
      router.replace({ pathname: '/vault/[id]', params: { id: vault.id } });
    },
  });

  return (
    <Screen>
      <Body muted>
        A shared vault holds memories you preserve together. You can invite people after creating it.
      </Body>
      <Field label="Name" placeholder="Our First Trip" value={name} onChangeText={setName} maxLength={120} />
      <ErrorText error={create.error} />
      <Button title="Create vault" onPress={() => create.mutate()} loading={create.isPending} disabled={!name.trim()} />
    </Screen>
  );
}
