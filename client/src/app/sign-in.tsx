import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ApiError } from '@/api/client';
import { Button, ErrorText, Field, LockMark, TextLink, Title } from '@/components/ui';
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
        <View style={{ padding: space.xl, gap: space.lg, maxWidth: 420, width: '100%', alignSelf: 'center' }}>
          <LockMark size={48} />
          <Title subtitle="Enter the 6-digit code from your authenticator app, or one of your recovery codes.">
            Two-step verification
          </Title>
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
            variant="ghost"
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
      <View style={{ padding: space.xl, gap: space.lg, maxWidth: 420, width: '100%', alignSelf: 'center' }}>
        <View style={{ gap: space.xl, marginBottom: space.md }}>
          <LockMark />
          <Title subtitle="Some memories deserve to be permanent.">Welcome back</Title>
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
        <Button title="Create an account" variant="secondary" onPress={() => router.push('/sign-up')} />
        <View style={{ alignItems: 'center', paddingTop: space.sm }}>
          <TextLink title="Forgot your password?" muted onPress={() => router.push('/forgot-password')} />
        </View>
      </View>
    </SafeAreaView>
  );
}
