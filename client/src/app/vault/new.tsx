import { useMutation, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { Switch, View } from 'react-native';

import { api } from '@/api/lock';
import { Body, Button, ErrorText, Field, Screen } from '@/components/ui';
import { useKeyBundle } from '@/lib/useSealed';
import { space, useTheme } from '@/theme';

export default function NewVault() {
  const queryClient = useQueryClient();
  const t = useTheme();
  const bundle = useKeyBundle();
  const [name, setName] = useState('');
  const [sealed, setSealed] = useState(false);
  const create = useMutation({
    mutationFn: () => api.createVault('SHARED', name.trim(), sealed ? 'SEALED' : 'SERVER'),
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
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md }}>
        <View style={{ flex: 1 }}>
          <Body style={{ fontWeight: '600' }}>Sealed (end-to-end encrypted)</Body>
          <Body muted>
            Only members’ devices can read it, not even Lock. Search, export and photos run on your device and are coming
            soon. Can’t be changed later.
          </Body>
        </View>
        <Switch value={sealed} onValueChange={setSealed} trackColor={{ true: t.accent }} />
      </View>
      {sealed && bundle.data === null ? (
        <Body style={{ color: t.danger }}>Set up your Sealed Vault keys in Settings first. Everyone you invite will need them too.</Body>
      ) : null}
      <ErrorText error={create.error} />
      <Button title="Create vault" onPress={() => create.mutate()} loading={create.isPending} disabled={!name.trim() || (sealed && !bundle.data)} />
    </Screen>
  );
}
