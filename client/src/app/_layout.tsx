import '@/lib/cryptoRandom'; // must be first: secure randomness for @noble/* on native
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Platform } from 'react-native';

import { Loading } from '@/components/ui';
import { sweepSealedCache } from '@/lib/sealedFiles';
import { SessionProvider, useSession } from '@/lib/session';
import { useTheme } from '@/theme';

export default function RootLayout() {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } }),
  );
  // Plaintext left in the cache by a crash or a killed app (security review F4).
  useEffect(() => {
    if (Platform.OS !== 'web') sweepSealedCache();
  }, []);
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
        headerTitleStyle: { fontSize: 17, fontWeight: '600' },
        headerShadowVisible: false,
        headerBackButtonDisplayMode: 'minimal',
        contentStyle: { backgroundColor: t.background },
      }}>
      <Stack.Protected guard={!signedIn}>
        <Stack.Screen name="sign-in" options={{ headerShown: false }} />
        <Stack.Screen name="sign-up" options={{ title: 'Create account' }} />
        <Stack.Screen name="forgot-password" options={{ title: 'Forgot password' }} />
      </Stack.Protected>

      {/* Reachable signed in or not: the emailed link may be opened on a device that is signed in. */}
      <Stack.Screen name="reset-password" options={{ title: 'Choose a new password' }} />

      <Stack.Protected guard={signedIn}>
        <Stack.Screen name="index" options={{ title: '' }} />
        <Stack.Screen name="vault/new" options={{ title: 'New shared vault', presentation: 'modal' }} />
        <Stack.Screen name="vault/[id]/index" options={{ title: 'Vault' }} />
        <Stack.Screen name="vault/[id]/members" options={{ title: 'Members' }} />
        <Stack.Screen name="vault/[id]/map" options={{ title: 'Map' }} />
        <Stack.Screen name="map" options={{ title: 'All memories' }} />
        <Stack.Screen name="vault/[id]/compose" options={{ title: 'New memory', presentation: 'modal' }} />
        <Stack.Screen name="memory/[id]/index" options={{ title: 'Memory' }} />
        <Stack.Screen name="memory/[id]/edit" options={{ title: 'Edit draft', presentation: 'modal' }} />
        <Stack.Screen name="memory/[id]/relive" options={{ title: 'Relive' }} />
        <Stack.Screen name="memory/[id]/delete" options={{ title: 'Request deletion', presentation: 'modal' }} />
        <Stack.Screen name="vault/[id]/export" options={{ title: 'Export' }} />
        <Stack.Screen name="settings" options={{ title: 'Settings' }} />
        <Stack.Screen name="notifications" options={{ title: 'Notifications' }} />
        <Stack.Screen name="two-step" options={{ title: 'Two-step verification' }} />
        <Stack.Screen name="sealed-keys" options={{ title: 'Sealed Vault keys' }} />
        <Stack.Screen name="approve-device" options={{ title: 'Approve a device', presentation: 'modal' }} />
        <Stack.Screen name="delete-account" options={{ title: 'Delete account', presentation: 'modal' }} />
      </Stack.Protected>
    </Stack>
  );
}
