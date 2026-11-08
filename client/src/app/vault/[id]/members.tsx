import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Field, Label, Loading, Screen } from '@/components/ui';
import { confirm } from '@/lib/confirm';
import { acceptNewKey, markVerified, observeMemberKeys, usePinStatus, type PinStatus } from '@/lib/keyPins';
import { formatDate } from '@/lib/format';
import { mono, space, useTheme } from '@/theme';

export default function Members() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [email, setEmail] = useState('');
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  const members = useQuery({ queryKey: ['members', id], queryFn: () => api.members(id) });
  // Sealed vaults (security review F1): each member's key is pinned on this device the first time it is
  // seen. Members compare the fingerprints in person, then mark each other verified here.
  const sealedVault = vault.data?.encryption === 'SEALED';
  useQuery({
    queryKey: ['vaultKeys', id, 'pins'],
    queryFn: async () => {
      const state = await api.vaultKeys(id);
      await observeMemberKeys(me.data!.id, state.members.filter((m) => m.userId !== me.data!.id));
      return true;
    },
    enabled: sealedVault && !!me.data,
  });
  const pinOf = usePinStatus(sealedVault ? me.data?.id : undefined);
  const trust = useMutation({
    mutationFn: ({ userId, accept }: { userId: string; accept: boolean }) =>
      accept ? acceptNewKey(me.data!.id, userId) : markVerified(me.data!.id, userId),
  });
  const isOwner = !!me.data && vault.data?.ownerId === me.data.id;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['members', id] });
  const invite = useMutation({
    mutationFn: () => api.invite(id, email.trim()),
    onSuccess: () => {
      setEmail('');
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: (userId: string) => api.removeMember(id, userId), onSuccess: refresh });
  const leave = useMutation({
    mutationFn: () => api.leaveVault(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['vaults'] });
      router.replace('/');
    },
  });

  const visible = members.data?.filter((m) => m.status === 'ACTIVE' || m.status === 'INVITED') ?? [];

  return (
    <Screen>
      {members.isPending ? <Loading /> : null}
      <View style={{ gap: space.sm }}>
        <Label>People</Label>
        {visible.map((m) => (
          <Card key={m.userId}>
            <Body style={{ fontWeight: '600' }}>{m.userId === me.data?.id ? 'You' : (m.displayName ?? `Member ${m.userId.slice(0, 8)}`)}</Body>
            <Body muted>
              {m.role === 'OWNER' ? 'Owner' : 'Member'} ·{' '}
              {m.status === 'INVITED' ? 'invitation pending' : `joined ${formatDate(m.joinedAt)}`}
            </Body>
            {sealedVault && m.userId !== me.data?.id ? <KeyTrust status={pinOf(m.userId)} onTrust={(accept) => trust.mutate({ userId: m.userId, accept })} /> : null}
            {isOwner && m.role !== 'OWNER' ? (
              <Button
                title={m.status === 'INVITED' ? 'Revoke invitation' : 'Remove'}
                variant="secondary"
                onPress={() =>
                  confirm(
                    'Remove member?',
                    'They lose access from now on, and new memories get a new encryption key. Memories they contributed stay in the vault.',
                    'Remove',
                    () => remove.mutate(m.userId),
                  )
                }
              />
            ) : null}
          </Card>
        ))}
        <ErrorText error={members.error ?? remove.error} />
      </View>

      {isOwner ? (
        <View style={{ gap: space.md }}>
          <Label>Invite someone</Label>
          <Field
            label="Their Lock account email"
            value={email}
            onChangeText={setEmail}
            autoCapitalize="none"
            keyboardType="email-address"
          />
          <ErrorText error={invite.error} />
          <Button title="Send invitation" onPress={() => invite.mutate()} loading={invite.isPending} disabled={!email.trim()} />
        </View>
      ) : null}

      <View style={{ gap: space.sm }}>
        <Body muted>
          Leaving removes your access. Every memory stays preserved for the others, and nobody can delete what you
          contributed.
        </Body>
        <ErrorText error={leave.error} />
        <Button
          title="Leave vault"
          variant="danger"
          loading={leave.isPending}
          onPress={() => confirm('Leave this vault?', 'You will lose access to its memories.', 'Leave', () => leave.mutate())}
        />
      </View>
    </Screen>
  );
}

/** A member's Sealed Vault key as this device pinned it, with the action that fits its state. */
function KeyTrust({ status, onTrust }: { status: PinStatus; onTrust: (acceptNewKey: boolean) => void }) {
  const t = useTheme();
  if (status.state === 'unpinned') return null;
  if (status.state === 'changed') {
    return (
      <View style={{ gap: space.sm }}>
        <Body style={{ color: t.danger, fontSize: 14 }}>
          Their key changed. This device won’t share vault keys with them until you compare the new fingerprint with
          them in person.
        </Body>
        <Body muted selectable style={{ fontFamily: mono, fontSize: 12 }}>
          Was {status.fingerprint}
          {'\n'}Now {status.newFingerprint}
        </Body>
        <Button
          title="I compared it: accept the new key"
          variant="secondary"
          onPress={() =>
            confirm(
              'Accept the new key?',
              'Only if they showed you this exact new fingerprint on their own device. A changed key can mean someone is trying to read the vault.',
              'Accept',
              () => onTrust(true),
            )
          }
        />
      </View>
    );
  }
  return (
    <View style={{ gap: space.xs }}>
      <Body muted selectable style={{ fontFamily: mono, fontSize: 12 }}>
        Key {status.fingerprint}
      </Body>
      {status.state === 'verified' ? (
        <Body style={{ color: t.locked, fontSize: 13, fontWeight: '600' }}>Verified in person</Body>
      ) : (
        <Button
          title="Mark as verified"
          variant="ghost"
          onPress={() =>
            confirm(
              'Mark as verified?',
              'Do this after they showed you the same fingerprint on their own device (Settings → Sealed Vault keys).',
              'Verified',
              () => onTrust(false),
            )
          }
        />
      )}
    </View>
  );
}
