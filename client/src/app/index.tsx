import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, router, Stack } from 'expo-router';
import { Pressable, Text, View } from 'react-native';

import { api } from '@/api/lock';
import { MemoryCard } from '@/components/MemoryCard';
import { Body, Button, Card, ErrorText, Label, Loading, Screen } from '@/components/ui';
import { space, useTheme } from '@/theme';

export default function Home() {
  const t = useTheme();
  const queryClient = useQueryClient();
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

  return (
    <Screen>
      <Stack.Screen
        options={{
          headerRight: () => (
            <Link href="/settings" asChild>
              <Pressable accessibilityLabel="Settings" hitSlop={12}>
                <Text style={{ color: t.accent, fontSize: 16 }}>Settings</Text>
              </Pressable>
            </Link>
          ),
        }}
      />

      {onThisDay.data && onThisDay.data.length > 0 ? (
        <View style={{ gap: space.sm }}>
          <Label>On this day</Label>
          {onThisDay.data.map((m) => (
            <MemoryCard key={m.id} memory={m} />
          ))}
        </View>
      ) : null}

      {invitations.data && invitations.data.length > 0 ? (
        <View style={{ gap: space.sm }}>
          <Label>Invitations</Label>
          {invitations.data.map((inv) => (
            <Card key={inv.vaultId}>
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
        </View>
      ) : null}

      <View style={{ gap: space.sm }}>
        <Label>Your vaults</Label>
        {vaults.isPending ? <Loading /> : null}
        <ErrorText error={vaults.error} />
        {vaults.data?.map((v) => (
          <Card key={v.id} onPress={() => router.push({ pathname: '/vault/[id]', params: { id: v.id } })}>
            <Body style={{ fontSize: 18, fontWeight: '600' }}>{v.name}</Body>
            <Body muted>
              {v.type === 'PERSONAL' ? 'Private · only you' : 'Shared'}
              {v.state === 'DORMANT' ? ' · dormant' : ''}
            </Body>
          </Card>
        ))}
      </View>

      <Button title="New shared vault" variant="secondary" onPress={() => router.push('/vault/new')} />
    </Screen>
  );
}
