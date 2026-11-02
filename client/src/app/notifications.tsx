import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Platform, Switch, View } from 'react-native';

import { api } from '@/api/lock';
import type { NotificationCategory, NotificationPreferences } from '@/api/types';
import { Body, Card, ErrorText, Loading, Screen } from '@/components/ui';
import { space } from '@/theme';

const LABELS: Record<NotificationCategory, { title: string; hint: string }> = {
  MEMORY_LOCKED: { title: 'Memory locked', hint: 'When the cooling-off ends and a memory is preserved.' },
  CAPSULE_REVEALED: { title: 'Time capsule opened', hint: 'When a capsule in one of your vaults opens.' },
  ON_THIS_DAY: { title: 'On this day', hint: 'A daily nudge when you have a memory from this date.' },
  VAULT_INVITATION: { title: 'Vault invitations', hint: 'When someone invites you to a shared vault.' },
  ASSET_REJECTED: { title: 'File not added', hint: 'When a file fails the safety scan.' },
  EXPORT_READY: { title: 'Export ready', hint: 'When a ZIP export can be downloaded.' },
  DELETION_REMINDER: {
    title: 'Deletion reminders',
    hint: '7 days and 1 day before a memory or your account is deleted. Always on, so nothing is lost by surprise.',
  },
};

export default function Notifications() {
  const queryClient = useQueryClient();
  const prefs = useQuery({ queryKey: ['notification-preferences'], queryFn: api.notificationPreferences });
  const update = useMutation({
    mutationFn: (changes: Partial<NotificationPreferences>) => api.updateNotificationPreferences(changes),
    onSuccess: (next) => queryClient.setQueryData(['notification-preferences'], next),
  });

  if (!prefs.data) return prefs.error ? <ErrorText error={prefs.error} /> : <Loading />;

  return (
    <Screen>
      {Platform.OS === 'web' ? <Body muted>Push notifications arrive on the iOS and Android apps.</Body> : null}
      {(Object.keys(LABELS) as NotificationCategory[]).map((category) => (
        <Card key={category}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.md }}>
            <View style={{ flex: 1, gap: space.xs }}>
              <Body style={{ fontWeight: '600' }}>{LABELS[category].title}</Body>
              <Body muted style={{ fontSize: 14 }}>
                {LABELS[category].hint}
              </Body>
            </View>
            <Switch
              accessibilityLabel={LABELS[category].title}
              value={prefs.data[category]}
              disabled={category === 'DELETION_REMINDER' || update.isPending}
              onValueChange={(enabled) => update.mutate({ [category]: enabled })}
            />
          </View>
        </Card>
      ))}
      <ErrorText error={update.error} />
    </Screen>
  );
}
