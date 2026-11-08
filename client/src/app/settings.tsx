import { useMutation, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { API_URL } from '@/api/client';
import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Field, Group, Monogram, ProgressBar, Row, Screen, Section } from '@/components/ui';
import { useSession } from '@/lib/session';
import { formatBytes } from '@/lib/format';
import { space, useTheme } from '@/theme';

export default function Settings() {
  const { signOut, changePassword } = useSession();
  const t = useTheme();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const storage = useQuery({ queryKey: ['storage'], queryFn: api.storage });
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const change = useMutation({
    mutationFn: () => changePassword(current, next),
    onSuccess: () => {
      setCurrent('');
      setNext('');
    },
  });

  return (
    <Screen>
      {me.data ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.lg }}>
          <Monogram text={me.data.displayName} size={56} tint />
          <View style={{ flex: 1, gap: 2 }}>
            <Body style={{ fontSize: 20, fontWeight: '600', letterSpacing: -0.3 }}>{me.data.displayName}</Body>
            <Body muted style={{ fontSize: 15 }}>{me.data.email}</Body>
          </View>
        </View>
      ) : null}

      {storage.data ? (
        <Section title="Storage">
          <Card style={{ gap: space.md }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <Body style={{ fontWeight: '600' }}>
                {formatBytes(storage.data.usedBytes)}
                <Body muted> of {formatBytes(storage.data.limitBytes)}</Body>
              </Body>
              <Body muted style={{ fontSize: 14 }}>
                {storage.data.plan.charAt(0).toUpperCase() + storage.data.plan.slice(1)} plan
              </Body>
            </View>
            <ProgressBar
              value={storage.data.usedBytes}
              max={storage.data.limitBytes}
              tone={storage.data.remainingBytes === 0 ? 'danger' : undefined}
            />
            {storage.data.remainingBytes === 0 ? (
              <Body style={{ fontSize: 14, color: t.danger }}>
                Your storage is full. Everything you have preserved stays safe; new photos and videos can’t be added.
              </Body>
            ) : null}
          </Card>
        </Section>
      ) : null}

      <Section title="Security">
        <Group>
          <Row title="Two-step verification" onPress={() => router.push('/two-step')} />
          <Row title="Sealed Vault keys" onPress={() => router.push('/sealed-keys')} />
          <Row title="Notifications" onPress={() => router.push('/notifications')} />
        </Group>
      </Section>

      <Section title="Change password">
        <Body muted style={{ fontSize: 15 }}>
          Your memories stay readable. Changing your password never re-encrypts them.
        </Body>
        <Field label="Current password" value={current} onChangeText={setCurrent} secureTextEntry />
        <Field label="New password (at least 10 characters)" value={next} onChangeText={setNext} secureTextEntry />
        <ErrorText error={change.error} />
        {change.isSuccess ? <Body style={{ color: t.locked }}>Password changed.</Body> : null}
        <Button
          title="Change password"
          onPress={() => change.mutate()}
          loading={change.isPending}
          disabled={!current || next.length < 10}
        />
      </Section>

      <Section title="Account">
        <Group>
          <Row title="Sign out" onPress={signOut} trailing={<View />} />
          <Row title="Delete account" tone="danger" onPress={() => router.push('/delete-account')} />
        </Group>
      </Section>

      <Body style={{ fontSize: 12, color: t.textFaint, textAlign: 'center' }}>API: {API_URL}</Body>
    </Screen>
  );
}
