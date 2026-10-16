import { Link } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Body, Button, ErrorText, Field, Title } from '@/components/ui';
import { useSession } from '@/lib/session';
import { space, useTheme } from '@/theme';

export default function SignIn() {
  const { signIn } = useSession();
  const t = useTheme();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await signIn(email, password);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.background, justifyContent: 'center' }}>
      <View style={{ padding: space.xl, gap: space.lg, maxWidth: 480, width: '100%', alignSelf: 'center' }}>
        <View style={{ gap: space.xs, marginBottom: space.lg }}>
          <Title>Lock</Title>
          <Body muted>Some memories deserve to be permanent.</Body>
        </View>
        <Field
          label="Email"
          value={email}
          onChangeText={setEmail}
          autoCapitalize="none"
          autoComplete="email"
          keyboardType="email-address"
          textContentType="emailAddress"
        />
        <Field
          label="Password"
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoComplete="current-password"
          textContentType="password"
          onSubmitEditing={submit}
        />
        <ErrorText error={error} />
        <Button title="Sign in" onPress={submit} loading={busy} disabled={!email || !password} />
        <Link href="/sign-up" style={{ color: t.accent, textAlign: 'center', fontSize: 16 }}>
          New here? Create an account
        </Link>
      </View>
    </SafeAreaView>
  );
}
