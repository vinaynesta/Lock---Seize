import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import { MfaCodeField, useMfaEnabled } from '@/components/MfaCodeField';
import { Body, Button, Card, ErrorText, Field, Loading, Screen } from '@/components/ui';
import { formatDate } from '@/lib/format';

/**
 * Account deletion (Policy C): your personal vault is deleted with everything in it. Memories you
 * locked in shared vaults stay for the other members, credited to "Former member". It needs your
 * password and only happens after 30 days, so you can change your mind.
 */
export default function DeleteAccount() {
  const queryClient = useQueryClient();
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const mfaOn = useMfaEnabled();
  const pending = useQuery({
    queryKey: ['account-deletion'],
    queryFn: async () => {
      try {
        return await api.accountDeletion();
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null; // nothing scheduled
        throw e;
      }
    },
  });
  const request = useMutation({
    mutationFn: () => api.requestAccountDeletion(password, code),
    onSuccess: (d) => {
      setPassword('');
      setCode('');
      queryClient.setQueryData(['account-deletion'], d);
    },
  });
  const cancel = useMutation({
    mutationFn: api.cancelAccountDeletion,
    onSuccess: () => queryClient.setQueryData(['account-deletion'], null),
  });

  if (pending.isPending) return <Loading />;

  if (pending.data) {
    return (
      <Screen>
        <Card>
          <Body style={{ fontWeight: '600' }}>Account deletion scheduled</Body>
          <Body muted>
            Your account will be deleted after {formatDate(pending.data.executeAfter)}. Until then everything works as
            usual, and you can cancel.
          </Body>
        </Card>
        <ErrorText error={cancel.error} />
        <Button title="Keep my account" onPress={() => cancel.mutate()} loading={cancel.isPending} />
      </Screen>
    );
  }

  return (
    <Screen>
      {cancel.isSuccess ? <Body>Deletion cancelled. Your account and memories stay.</Body> : null}
      <Body style={{ fontWeight: '600' }}>What happens when you delete your account</Body>
      <Body muted>• Your personal vault and every memory in it are deleted permanently, including locked ones.</Body>
      <Body muted>
        • Memories you locked in shared vaults stay for the other members, shown as from a “Former member”. Your drafts
        there are deleted.
      </Body>
      <Body muted>• You are signed out everywhere and your email and name are removed.</Body>
      <Body muted>
        Nothing happens for 30 days, and you can cancel any time before then. Consider exporting your vaults first.
      </Body>
      <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry autoComplete="current-password" />
      <MfaCodeField value={code} onChangeText={setCode} />
      <ErrorText error={request.error ?? pending.error} />
      <Button
        title="Schedule account deletion"
        variant="danger"
        onPress={() => request.mutate()}
        loading={request.isPending}
        disabled={!password || (mfaOn && !code.trim())}
      />
    </Screen>
  );
}
