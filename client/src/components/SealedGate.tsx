import { router } from 'expo-router';

import type { SealedState } from '@/lib/useSealed';

import { Body, Button, Card, ErrorText, Loading } from './ui';

/**
 * Shown in place of a sealed vault's content when this device can't open it yet. Returns null when the
 * vault is server-side or ready, so callers can render it unconditionally above their content.
 */
export function SealedGate({ state }: { state: SealedState }) {
  if (state.status === 'server' || state.status === 'ready') return null;
  if (state.status === 'loading') return <Loading />;
  if (state.status === 'error') {
    return (
      <Card>
        <Body style={{ fontWeight: '600' }}>This sealed vault can’t be opened on this device yet</Body>
        <ErrorText error={state.error} />
      </Card>
    );
  }
  return (
    <Card>
      <Body style={{ fontWeight: '600' }}>End-to-end encrypted</Body>
      <Body muted>
        {state.status === 'no-bundle'
          ? 'Set up your Sealed Vault keys to read and write here.'
          : 'Unlock your Sealed Vault keys on this device to read and write here.'}
      </Body>
      <Button title={state.status === 'no-bundle' ? 'Set up keys' : 'Unlock'} onPress={() => router.push('/sealed-keys')} />
    </Card>
  );
}
