import { useMutation, useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { API_URL } from '@/api/client';
import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Field, Label, Screen } from '@/components/ui';
import { useSession } from '@/lib/session';
import { formatBytes } from '@/lib/format';
import { radius, space, useTheme } from '@/theme';

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
        <Card>
          <Body style={{ fontWeight: '600' }}>{me.data.displayName}</Body>
          <Body muted>{me.data.email}</Body>
        </Card>
      ) : null}

      {storage.data ? (
        <View style={{ gap: space.sm }}>
          <Label>Storage</Label>
          <View
            accessibilityRole="progressbar"
            accessibilityValue={{ min: 0, max: storage.data.limitBytes, now: storage.data.usedBytes }}
            style={{ height: 8, borderRadius: radius.md, backgroundColor: t.surfaceMuted, overflow: 'hidden' }}>
            <View
              style={{
                height: '100%',
                width: `${Math.min(100, (storage.data.usedBytes / Math.max(1, storage.data.limitBytes)) * 100)}%`,
                backgroundColor: storage.data.remainingBytes === 0 ? t.danger : t.accent,
              }}
            />
          </View>
          <Body muted style={{ fontSize: 14 }}>
            {formatBytes(storage.data.usedBytes)} of {formatBytes(storage.data.limitBytes)} used ·{' '}
            {storage.data.plan.charAt(0).toUpperCase() + storage.data.plan.slice(1)} plan
          </Body>
          {storage.data.remainingBytes === 0 ? (
            <Body style={{ fontSize: 14, color: t.danger }}>
              Your storage is full. Everything you have preserved stays safe; new photos and videos can’t be added.
            </Body>
          ) : null}
        </View>
      ) : null}

      <View style={{ gap: space.md }}>
        <Label>Change password</Label>
        <Body muted>Your memories stay readable. Changing your password never re-encrypts them.</Body>
        <Field label="Current password" value={current} onChangeText={setCurrent} secureTextEntry />
        <Field label="New password (at least 10 characters)" value={next} onChangeText={setNext} secureTextEntry />
        <ErrorText error={change.error} />
        {change.isSuccess ? <Body>Password changed.</Body> : null}
        <Button
          title="Change password"
          onPress={() => change.mutate()}
          loading={change.isPending}
          disabled={!current || next.length < 10}
        />
      </View>

      <Button title="Two-step verification" variant="secondary" onPress={() => router.push('/two-step')} />
      <Button title="Notifications" variant="secondary" onPress={() => router.push('/notifications')} />
      <Button title="Sign out" variant="secondary" onPress={signOut} />
      <Button title="Delete account" variant="danger" onPress={() => router.push('/delete-account')} />
      <Body muted style={{ fontSize: 12 }}>API: {API_URL}</Body>
    </Screen>
  );
}
