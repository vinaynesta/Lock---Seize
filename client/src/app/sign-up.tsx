import { useState } from 'react';

import { Body, Button, ErrorText, Field, Screen } from '@/components/ui';
import { useSession } from '@/lib/session';

const MIN_PASSWORD = 10;

export default function SignUp() {
  const { signUp } = useSession();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await signUp(email, password, name);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen>
      <Body muted>A private vault is created for you automatically.</Body>
      <Field label="Your name" value={name} onChangeText={setName} autoComplete="name" />
      <Field
        label="Email"
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
      />
      <Field
        label={`Password (at least ${MIN_PASSWORD} characters)`}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoComplete="new-password"
        textContentType="newPassword"
      />
      <ErrorText error={error} />
      <Button
        title="Create account"
        onPress={submit}
        loading={busy}
        disabled={!name.trim() || !email || password.length < MIN_PASSWORD}
      />
    </Screen>
  );
}
