import { useQuery } from '@tanstack/react-query';

import { api } from '@/api/lock';

import { Field } from './ui';

/** Whether sensitive actions need an authenticator code (two-step verification is on). */
export function useMfaEnabled(): boolean {
  const status = useQuery({ queryKey: ['mfa'], queryFn: api.mfaStatus, staleTime: 60_000 });
  return status.data?.enabled ?? false;
}

/** The code field shown on sensitive actions, only when two-step verification is on. */
export function MfaCodeField({ value, onChangeText }: { value: string; onChangeText: (v: string) => void }) {
  if (!useMfaEnabled()) return null;
  return (
    <Field
      label="Authenticator code (or a recovery code)"
      value={value}
      onChangeText={onChangeText}
      autoCapitalize="none"
      autoComplete="one-time-code"
      textContentType="oneTimeCode"
    />
  );
}
