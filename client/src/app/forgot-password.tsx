import { useMutation } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';

import { api } from '@/api/lock';
import { Body, Button, ErrorText, Field, Screen } from '@/components/ui';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const send = useMutation({ mutationFn: () => api.requestPasswordReset(email.trim()) });

  if (send.isSuccess) {
    return (
      <Screen>
        <Body>
          If an account exists for {email.trim()}, we have emailed it a link to choose a new password. The link
          works once and expires in 30 minutes.
        </Body>
        <Button title="Back to sign in" variant="secondary" onPress={() => router.back()} />
      </Screen>
    );
  }

  return (
    <Screen>
      <Body muted>
        Enter the email you signed up with. Resetting your password signs out every device, but your memories stay
        exactly as they are.
      </Body>
      <Field
        label="Email"
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        textContentType="emailAddress"
        onSubmitEditing={() => send.mutate()}
      />
      <ErrorText error={send.error} />
      <Button title="Email me a reset link" onPress={() => send.mutate()} loading={send.isPending} disabled={!email.trim()} />
    </Screen>
  );
}
