import { useMutation } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { api } from '@/api/lock';
import { Body, Button, ErrorText, Field, Screen } from '@/components/ui';

const MIN_PASSWORD = 10;

/** Opened from the emailed link (`/reset-password?token=…`, or `lock://reset-password?token=…`). */
export default function ResetPassword() {
  const { token } = useLocalSearchParams<{ token?: string }>();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const reset = useMutation({ mutationFn: () => api.confirmPasswordReset(token ?? '', password) });

  if (!token) {
    return (
      <Screen>
        <Body>This reset link is incomplete. Open the link from the email again, or ask for a new one.</Body>
        <Button title="Ask for a new link" onPress={() => router.replace('/forgot-password')} />
      </Screen>
    );
  }

  if (reset.isSuccess) {
    return (
      <Screen>
        <Body>Your password has been changed and every device was signed out. Sign in with the new password.</Body>
        <Button title="Sign in" onPress={() => router.replace('/sign-in')} />
      </Screen>
    );
  }

  const mismatch = confirm.length > 0 && confirm !== password;
  return (
    <Screen>
      <Body muted>Choose a new password. Your memories are not affected.</Body>
      <Field
        label={`New password (at least ${MIN_PASSWORD} characters)`}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
      />
      <Field label="Repeat new password" value={confirm} onChangeText={setConfirm} secureTextEntry />
      {mismatch ? <ErrorText error="The passwords don't match." /> : null}
      <ErrorText error={reset.error} />
      <Button
        title="Set new password"
        onPress={() => reset.mutate()}
        loading={reset.isPending}
        disabled={password.length < MIN_PASSWORD || password !== confirm}
      />
    </Screen>
  );
}
