import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';

import { API_URL } from '@/api/client';
import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Field, Label, Screen } from '@/components/ui';
import { useSession } from '@/lib/session';
import { space } from '@/theme';

export default function Settings() {
  const { signOut, changePassword } = useSession();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
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

      <Button title="Sign out" variant="secondary" onPress={signOut} />
      <Body muted style={{ fontSize: 12 }}>API: {API_URL}</Body>
    </Screen>
  );
}
