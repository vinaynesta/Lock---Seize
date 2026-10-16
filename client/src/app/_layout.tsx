import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';

import { Loading } from '@/components/ui';
import { SessionProvider, useSession } from '@/lib/session';
import { useTheme } from '@/theme';

export default function RootLayout() {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } }),
  );
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <StatusBar style="auto" />
        <RootStack />
      </SessionProvider>
    </QueryClientProvider>
  );
}

function RootStack() {
  const { loading, signedIn } = useSession();
  const t = useTheme();

  if (loading) return <Loading />;

  return (
    <Stack
      screenOptions={{
        headerStyle: { backgroundColor: t.background },
        headerTintColor: t.text,
        headerShadowVisible: false,
        contentStyle: { backgroundColor: t.background },
      }}>
      <Stack.Protected guard={!signedIn}>
        <Stack.Screen name="sign-in" options={{ headerShown: false }} />
        <Stack.Screen name="sign-up" options={{ title: 'Create account' }} />
      </Stack.Protected>

      <Stack.Protected guard={signedIn}>
        <Stack.Screen name="index" options={{ title: 'Lock' }} />
        <Stack.Screen name="vault/new" options={{ title: 'New shared vault', presentation: 'modal' }} />
        <Stack.Screen name="vault/[id]/index" options={{ title: 'Vault' }} />
        <Stack.Screen name="vault/[id]/members" options={{ title: 'Members' }} />
        <Stack.Screen name="vault/[id]/compose" options={{ title: 'New memory', presentation: 'modal' }} />
        <Stack.Screen name="memory/[id]/index" options={{ title: 'Memory' }} />
        <Stack.Screen name="memory/[id]/edit" options={{ title: 'Edit draft', presentation: 'modal' }} />
        <Stack.Screen name="memory/[id]/relive" options={{ title: 'Relive' }} />
        <Stack.Screen name="memory/[id]/delete" options={{ title: 'Request deletion', presentation: 'modal' }} />
        <Stack.Screen name="settings" options={{ title: 'Settings' }} />
      </Stack.Protected>
    </Stack>
  );
}
