import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import { useState } from 'react';
import { Linking, Platform, Text, View } from 'react-native';

import { api } from '@/api/lock';
import type { MfaSetup } from '@/api/types';
import { Body, Button, Card, ErrorText, Field, Label, Loading, Screen } from '@/components/ui';
import { formatDate } from '@/lib/format';
import { space, useTheme } from '@/theme';

/** Two-step verification (TOTP): set up, view recovery codes once, or turn off. */
export default function TwoStep() {
  const t = useTheme();
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ['mfa'], queryFn: api.mfaStatus });
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState<string[] | null>(null);

  const done = (codes?: string[]) => {
    setCode('');
    setSetup(null);
    setRecovery(codes ?? null);
    void queryClient.invalidateQueries({ queryKey: ['mfa'] });
  };
  const start = useMutation({ mutationFn: api.mfaSetup, onSuccess: setSetup });
  const confirm = useMutation({ mutationFn: () => api.mfaConfirm(code.trim()), onSuccess: (r) => done(r.recoveryCodes) });
  const regenerate = useMutation({
    mutationFn: () => api.mfaRegenerateRecoveryCodes(code.trim()),
    onSuccess: (r) => done(r.recoveryCodes),
  });
  const disable = useMutation({ mutationFn: () => api.mfaDisable(code.trim()), onSuccess: () => done() });

  if (!status.data) return status.error ? <ErrorText error={status.error} /> : <Loading />;

  if (recovery) {
    return (
      <Screen>
        <Body style={{ fontWeight: '600' }}>Save your recovery codes</Body>
        <Body muted>
          If you lose your phone, each of these signs you in once. They will not be shown again. Keep them somewhere safe,
          away from this device.
        </Body>
        <Card>
          {recovery.map((c) => (
            <Text key={c} selectable style={{ color: t.text, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', fontSize: 16 }}>
              {c}
            </Text>
          ))}
        </Card>
        <Button title="Copy codes" variant="secondary" onPress={() => Clipboard.setStringAsync(recovery.join('\n'))} />
        <Button title="I've saved them" onPress={() => setRecovery(null)} />
      </Screen>
    );
  }

  if (status.data.enabled) {
    return (
      <Screen>
        <Card>
          <Body style={{ fontWeight: '600' }}>Two-step verification is on</Body>
          <Body muted>
            Since {status.data.enabledAt ? formatDate(status.data.enabledAt) : '—'} · {status.data.recoveryCodesLeft} recovery
            code{status.data.recoveryCodesLeft === 1 ? '' : 's'} left
          </Body>
        </Card>
        <Body muted>You need a code to sign in, export a vault, or request a deletion.</Body>
        <Field label="Current code" value={code} onChangeText={setCode} autoComplete="one-time-code" autoCapitalize="none" />
        <ErrorText error={regenerate.error ?? disable.error} />
        <Button
          title="New recovery codes"
          variant="secondary"
          onPress={() => regenerate.mutate()}
          loading={regenerate.isPending}
          disabled={code.trim().length < 6}
        />
        <Button
          title="Turn off two-step verification"
          variant="danger"
          onPress={() => disable.mutate()}
          loading={disable.isPending}
          disabled={code.trim().length < 6}
        />
      </Screen>
    );
  }

  if (setup) {
    return (
      <Screen>
        <Label>1. Add Lock to your authenticator app</Label>
        <Body muted>Open it on this phone, or enter the key by hand in any TOTP app (1Password, Google Authenticator, Authy…).</Body>
        {Platform.OS !== 'web' ? (
          <Button title="Open authenticator app" variant="secondary" onPress={() => Linking.openURL(setup.otpauthUri)} />
        ) : null}
        <Card>
          <Text selectable style={{ color: t.text, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', letterSpacing: 1 }}>
            {setup.secret.match(/.{1,4}/g)?.join(' ')}
          </Text>
        </Card>
        <Button title="Copy key" variant="secondary" onPress={() => Clipboard.setStringAsync(setup.secret)} />
        <View style={{ gap: space.md }}>
          <Label>2. Enter the 6-digit code it shows</Label>
          <Field label="Code" value={code} onChangeText={setCode} keyboardType="number-pad" autoComplete="one-time-code" />
        </View>
        <ErrorText error={confirm.error} />
        <Button title="Turn on" onPress={() => confirm.mutate()} loading={confirm.isPending} disabled={code.trim().length !== 6} />
      </Screen>
    );
  }

  return (
    <Screen>
      <Body>Add a second step to signing in: a code from an authenticator app on your phone.</Body>
      <Body muted>
        Once it is on, signing in, exporting a vault and requesting a deletion all need the code, so a stolen password alone
        is not enough. You also get recovery codes in case you lose your phone.
      </Body>
      <ErrorText error={start.error} />
      <Button title="Set up two-step verification" onPress={() => start.mutate()} loading={start.isPending} />
    </Screen>
  );
}
