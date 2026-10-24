import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Field, Label, Loading, Screen } from '@/components/ui';
import { confirm } from '@/lib/confirm';
import { formatDate } from '@/lib/format';
import { space } from '@/theme';

export default function Members() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [email, setEmail] = useState('');
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  const members = useQuery({ queryKey: ['members', id], queryFn: () => api.members(id) });
  const isOwner = !!me.data && vault.data?.ownerId === me.data.id;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['members', id] });
  const invite = useMutation({
    mutationFn: () => api.invite(id, email.trim()),
    onSuccess: () => {
      setEmail('');
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: (userId: string) => api.removeMember(id, userId), onSuccess: refresh });
  const leave = useMutation({
    mutationFn: () => api.leaveVault(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['vaults'] });
      router.replace('/');
    },
  });

  const visible = members.data?.filter((m) => m.status === 'ACTIVE' || m.status === 'INVITED') ?? [];

  return (
    <Screen>
      {members.isPending ? <Loading /> : null}
      <View style={{ gap: space.sm }}>
        <Label>People</Label>
        {visible.map((m) => (
          <Card key={m.userId}>
            <Body style={{ fontWeight: '600' }}>{m.userId === me.data?.id ? 'You' : (m.displayName ?? `Member ${m.userId.slice(0, 8)}`)}</Body>
            <Body muted>
              {m.role === 'OWNER' ? 'Owner' : 'Member'} ·{' '}
              {m.status === 'INVITED' ? 'invitation pending' : `joined ${formatDate(m.joinedAt)}`}
            </Body>
            {isOwner && m.role !== 'OWNER' ? (
              <Button
                title={m.status === 'INVITED' ? 'Revoke invitation' : 'Remove'}
                variant="secondary"
                onPress={() =>
                  confirm(
                    'Remove member?',
                    'They lose access from now on, and new memories get a new encryption key. Memories they contributed stay in the vault.',
                    'Remove',
                    () => remove.mutate(m.userId),
                  )
                }
              />
            ) : null}
          </Card>
        ))}
        <ErrorText error={members.error ?? remove.error} />
      </View>

      {isOwner ? (
        <View style={{ gap: space.md }}>
          <Label>Invite someone</Label>
          <Field
            label="Their Lock account email"
            value={email}
            onChangeText={setEmail}
            autoCapitalize="none"
            keyboardType="email-address"
          />
          <ErrorText error={invite.error} />
          <Button title="Send invitation" onPress={() => invite.mutate()} loading={invite.isPending} disabled={!email.trim()} />
        </View>
      ) : null}

      <View style={{ gap: space.sm }}>
        <Body muted>
          Leaving removes your access. Every memory stays preserved for the others, and nobody can delete what you
          contributed.
        </Body>
        <ErrorText error={leave.error} />
        <Button
          title="Leave vault"
          variant="danger"
          loading={leave.isPending}
          onPress={() => confirm('Leave this vault?', 'You will lose access to its memories.', 'Leave', () => leave.mutate())}
        />
      </View>
    </Screen>
  );
}
