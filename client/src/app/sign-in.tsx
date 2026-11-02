import { Link } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiError } from '@/api/client';
import { Body, Button, ErrorText, Field, Title } from '@/components/ui';
import { useSession } from '@/lib/session';
import { space, useTheme } from '@/theme';

export default function SignIn() {
  const { signIn, completeMfa } = useSession();
  const t = useTheme();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [code, setCode] = useState('');

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      if (mfaToken) {
        await completeMfa(mfaToken, code);
      } else {
        const challenge = await signIn(email, password);
        if (challenge) setMfaToken(challenge.mfaToken);
      }
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.code === 'MFA_TICKET_INVALID') {
        setMfaToken(null); // took too long: start over with the password
        setCode('');
      }
    } finally {
      setBusy(false);
    }
  };

  if (mfaToken) {
    return (
      <SafeAreaView style={{ flex: 1, backgroundColor: t.background, justifyContent: 'center' }}>
        <View style={{ padding: space.xl, gap: space.lg, maxWidth: 480, width: '100%', alignSelf: 'center' }}>
          <Title>Two-step verification</Title>
          <Body muted>Enter the 6-digit code from your authenticator app, or one of your recovery codes.</Body>
          <Field
            label="Code"
            value={code}
            onChangeText={setCode}
            autoCapitalize="none"
            autoComplete="one-time-code"
            textContentType="oneTimeCode"
            onSubmitEditing={submit}
            autoFocus
          />
          <ErrorText error={error} />
          <Button title="Verify" onPress={submit} loading={busy} disabled={code.trim().length < 6} />
          <Button
            title="Back"
            variant="secondary"
            onPress={() => {
              setMfaToken(null);
              setCode('');
              setError(null);
            }}
          />
        </View>
      </SafeAreaView>
    );
  }

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
        <Link href="/forgot-password" style={{ color: t.textMuted, textAlign: 'center', fontSize: 15 }}>
          Forgot your password?
        </Link>
      </View>
    </SafeAreaView>
  );
}
