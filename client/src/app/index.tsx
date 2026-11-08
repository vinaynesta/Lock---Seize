import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, Stack } from 'expo-router';
import { View } from 'react-native';

import { api } from '@/api/lock';
import { MemoryCard } from '@/components/MemoryCard';
import {
  Body,
  Button,
  Card,
  Empty,
  ErrorText,
  Group,
  Loading,
  Monogram,
  Row,
  Screen,
  Section,
  TextLink,
  Title,
} from '@/components/ui';
import { space } from '@/theme';

function greeting(): string {
  const h = new Date().getHours();
  return h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
}

export default function Home() {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const vaults = useQuery({ queryKey: ['vaults'], queryFn: api.vaults });
  const invitations = useQuery({ queryKey: ['invitations'], queryFn: api.invitations });
  const onThisDay = useQuery({ queryKey: ['on-this-day'], queryFn: api.onThisDay });

  const respond = useMutation({
    mutationFn: ({ vaultId, accept }: { vaultId: string; accept: boolean }) => api.respondToInvitation(vaultId, accept),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['invitations'] });
      queryClient.invalidateQueries({ queryKey: ['vaults'] });
    },
  });

  const firstName = me.data?.displayName.split(' ')[0];

  return (
    <Screen>
      <Stack.Screen options={{ headerRight: () => <TextLink title="Settings" onPress={() => router.push('/settings')} /> }} />

      <View style={{ gap: space.md }}>
        <Title subtitle="Some memories deserve to be permanent.">
          {greeting()}
          {firstName ? `, ${firstName}` : ''}
        </Title>
        <View style={{ alignSelf: 'flex-start' }}>
          <TextLink title="📍 Map of all memories" onPress={() => router.push('/map')} />
        </View>
      </View>

      {onThisDay.data && onThisDay.data.length > 0 ? (
        <Section title="On this day">
          {onThisDay.data.map((m) => (
            <MemoryCard key={m.id} memory={m} />
          ))}
        </Section>
      ) : null}

      {invitations.data && invitations.data.length > 0 ? (
        <Section title="Invitations">
          {invitations.data.map((inv) => (
            <Card key={inv.vaultId} style={{ gap: space.lg }}>
              <Body>You’ve been invited to a shared vault.</Body>
              <View style={{ flexDirection: 'row', gap: space.sm }}>
                <View style={{ flex: 1 }}>
                  <Button title="Accept" onPress={() => respond.mutate({ vaultId: inv.vaultId, accept: true })} />
                </View>
                <View style={{ flex: 1 }}>
                  <Button
                    title="Decline"
                    variant="secondary"
                    onPress={() => respond.mutate({ vaultId: inv.vaultId, accept: false })}
                  />
                </View>
              </View>
            </Card>
          ))}
          <ErrorText error={respond.error} />
        </Section>
      ) : null}

      <Section title="Your vaults" action={<TextLink title="+ New" onPress={() => router.push('/vault/new')} />}>
        {vaults.isPending ? <Loading /> : null}
        <ErrorText error={vaults.error} />
        {vaults.data && vaults.data.length === 0 ? <Empty title="No vaults yet" /> : null}
        {vaults.data && vaults.data.length > 0 ? (
          <Group>
            {vaults.data.map((v) => (
              <Row
                key={v.id}
                title={v.name}
                subtitle={[
                  v.type === 'PERSONAL' ? 'Private' : 'Shared',
                  v.encryption === 'SEALED' ? 'End-to-end encrypted' : null,
                  v.state === 'DORMANT' ? 'Dormant' : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
                leading={<Monogram text={v.name} tint={v.type === 'PERSONAL'} />}
                onPress={() => router.push({ pathname: '/vault/[id]', params: { id: v.id } })}
              />
            ))}
          </Group>
        ) : null}
      </Section>
    </Screen>
  );
}
