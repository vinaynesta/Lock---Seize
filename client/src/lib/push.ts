import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { router } from 'expo-router';
import { Platform } from 'react-native';

import { api } from '@/api/lock';
import type { DevicePlatform } from '@/api/types';

/**
 * Push notifications (roadmap 2.7). Native only: Expo push tokens need a physical device and an EAS
 * project id (`extra.eas.projectId` in app.json). Everything here is best effort: a refused
 * permission or a simulator just means no push.
 */

let registeredToken: string | null = null;

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

function platform(): DevicePlatform | null {
  if (Platform.OS === 'ios') return 'IOS';
  if (Platform.OS === 'android') return 'ANDROID';
  return null;
}

/** Asks for permission (once), gets this device's Expo push token and registers it with the API. */
export async function registerForPush(): Promise<void> {
  const os = platform();
  if (!os || !Device.isDevice) return;
  try {
    const current = await Notifications.getPermissionsAsync();
    const granted = current.granted || (current.canAskAgain && (await Notifications.requestPermissionsAsync()).granted);
    if (!granted) return;
    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('default', {
        name: 'Lock',
        importance: Notifications.AndroidImportance.DEFAULT,
      });
    }
    const projectId =
      (Constants.expoConfig?.extra?.eas?.projectId as string | undefined) ?? Constants.easConfig?.projectId;
    const { data: token } = await Notifications.getExpoPushTokenAsync(projectId ? { projectId } : undefined);
    await api.registerDevice(token, os);
    registeredToken = token;
  } catch {
    // No push on this device; the app works the same without it.
  }
}

/** On sign-out: stop notifying this device. Must run while the session is still valid. */
export async function unregisterFromPush(): Promise<void> {
  const token = registeredToken;
  registeredToken = null;
  if (token) await api.unregisterDevice(token).catch(() => undefined);
}

/** Opens the screen a notification is about when the user taps it. Returns an unsubscribe function. */
export function handleNotificationTaps(): () => void {
  const open = (data: Record<string, unknown> | undefined) => {
    if (!data) return;
    if (typeof data.memoryId === 'string') router.push(`/memory/${data.memoryId}`);
    else if (typeof data.vaultId === 'string' && data.category === 'VAULT_INVITATION') router.push('/');
    else if (data.screen === 'on-this-day') router.push('/');
    else if (data.category === 'DELETION_REMINDER') router.push('/settings');
  };
  if (!platform()) return () => undefined;
  const last = Notifications.getLastNotificationResponse();
  if (last) open(last.notification.request.content.data);
  const sub = Notifications.addNotificationResponseReceivedListener((r) => open(r.notification.request.content.data));
  return () => sub.remove();
}
