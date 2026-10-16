import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { DeletionRequest } from '@/api/types';
import { Body, Button, Card, ErrorText, Field, Screen } from '@/components/ui';
import { formatDate } from '@/lib/format';

/**
 * Locked memories are protected from casual deletion, not impossible to delete: this needs your
 * password and only takes effect after a 30-day grace period you can cancel.
 */
export default function RequestDeletion() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [password, setPassword] = useState('');
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState<DeletionRequest | null>(null);

  const request = useMutation({
    mutationFn: () => api.requestDeletion(id, password, reason.trim() || undefined),
    onSuccess: (d) => {
      setPassword('');
      setPending(d);
    },
  });
  const cancel = useMutation({
    mutationFn: () => api.cancelDeletion(id),
    onSuccess: () => {
      setPending(null);
      queryClient.invalidateQueries({ queryKey: ['memory', id] });
    },
  });

  const alreadyPending = request.error instanceof ApiError && request.error.code === 'DELETION_PENDING';

  if (pending || alreadyPending) {
    return (
      <Screen>
        <Card>
          <Body style={{ fontWeight: '600' }}>Deletion scheduled</Body>
          <Body muted>
            {pending
              ? `This memory will be permanently deleted after ${formatDate(pending.executeAfter)}.`
              : 'A deletion request is already pending for this memory.'}{' '}
            Until then it stays preserved, and you can cancel.
          </Body>
        </Card>
        <ErrorText error={cancel.error} />
        <Button title="Cancel deletion" onPress={() => cancel.mutate()} loading={cancel.isPending} />
        {cancel.isSuccess ? <Body>Deletion cancelled. Your memory stays preserved.</Body> : null}
      </Screen>
    );
  }

  return (
    <Screen>
      <Body>Locked memories are protected from accidental deletion.</Body>
      <Body muted>
        If you really want it gone, confirm with your password. It will be deleted permanently after 30 days, and you can
        cancel at any time before then.
      </Body>
      <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry autoComplete="current-password" />
      <Field label="Reason (optional, private)" value={reason} onChangeText={setReason} maxLength={500} />
      <ErrorText error={request.error} />
      <Button
        title="Schedule deletion"
        variant="danger"
        onPress={() => request.mutate()}
        loading={request.isPending}
        disabled={!password}
      />
    </Screen>
  );
}
