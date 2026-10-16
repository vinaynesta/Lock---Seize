import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as ImagePicker from 'expo-image-picker';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { api } from '@/api/lock';
import type { Asset, Memory } from '@/api/types';
import { uploadPickedAsset } from '@/api/upload';
import { AssetGallery } from '@/components/AssetGallery';
import { Body, Button, Card, ErrorText, Field, Label, Loading, Screen, StateBadge, Title } from '@/components/ui';
import { confirm } from '@/lib/confirm';
import { formatDate, formatDateTime, timeUntil } from '@/lib/format';
import { space, useTheme } from '@/theme';

export default function MemoryDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const queryClient = useQueryClient();
  const memory = useQuery({ queryKey: ['memory', id], queryFn: () => api.memory(id) });
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });

  const onUpdated = (m: Memory) => {
    queryClient.setQueryData(['memory', id], m);
    queryClient.invalidateQueries({ queryKey: ['timeline', m.vaultId] });
  };

  if (memory.isPending) return <Loading />;
  if (memory.error) {
    return (
      <Screen>
        <ErrorText error={memory.error} />
      </Screen>
    );
  }

  const m = memory.data;
  const isCreator = me.data?.id === m.createdBy;

  return (
    <Screen>
      <View style={{ gap: space.sm }}>
        <StateBadge state={m.state} capsule={m.sealedCapsule} />
        <Title>{m.sealedCapsule ? 'Sealed time capsule' : m.title}</Title>
        <Body muted>
          {formatDate(m.occurredAt)}
          {m.location && !m.sealedCapsule ? ` · ${m.location}` : ''}
        </Body>
      </View>

      {m.sealedCapsule ? (
        <Card>
          <Body>This memory is sealed until {formatDate(m.revealAt)}.</Body>
          <Body muted>Its content stays hidden, even from you, until then.</Body>
        </Card>
      ) : (
        <>
          {m.thought ? <Body style={{ fontSize: 17, lineHeight: 26 }}>{m.thought}</Body> : null}
          {m.feeling ? <Body muted>Feeling {m.feeling}</Body> : null}
        </>
      )}

      {m.state === 'DRAFT' ? <DraftActions memory={m} onUpdated={onUpdated} /> : <AssetGallery assets={m.assets} />}
      {m.state === 'SEALING' ? <SealingPanel memory={m} isCreator={isCreator} onUpdated={onUpdated} /> : null}
      {(m.state === 'LOCKED' || m.state === 'ARCHIVED') && !m.sealedCapsule ? (
        <LockedPanel memory={m} isCreator={isCreator} onUpdated={onUpdated} />
      ) : null}
    </Screen>
  );
}

function DraftActions({ memory, onUpdated }: { memory: Memory; onUpdated: (m: Memory) => void }) {
  const queryClient = useQueryClient();
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<unknown>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['memory', memory.id] });

  const pick = async () => {
    setUploadError(null);
    const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      setUploadError(new Error('Lock needs photo access to add media.'));
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images', 'videos'],
      allowsMultipleSelection: true,
      quality: 1,
    });
    if (result.canceled) return;
    setUploading(true);
    try {
      for (const picked of result.assets) {
        await uploadPickedAsset(memory.id, picked);
      }
    } catch (e) {
      setUploadError(e);
    } finally {
      setUploading(false);
      refresh();
    }
  };

  const removeAsset = useMutation({ mutationFn: (asset: Asset) => api.removeAsset(asset.id), onSuccess: refresh });
  const lock = useMutation({ mutationFn: () => api.lock(memory.id), onSuccess: onUpdated });
  const discard = useMutation({
    mutationFn: () => api.discardDraft(memory.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['timeline', memory.vaultId] });
      router.back();
    },
  });

  return (
    <View style={{ gap: space.lg }}>
      <AssetGallery assets={memory.assets} onRemove={(a) => removeAsset.mutate(a)} />
      <Button title={uploading ? 'Uploading…' : 'Add photos or videos'} variant="secondary" onPress={pick} loading={uploading} />
      <ErrorText error={uploadError ?? removeAsset.error} />

      <Button
        title="Edit draft"
        variant="secondary"
        onPress={() => router.push({ pathname: '/memory/[id]/edit', params: { id: memory.id } })}
      />

      <Card>
        <Body style={{ fontWeight: '600' }}>Lock this memory</Body>
        <Body muted>
          Locking preserves it as it is now. You get a 24-hour window to change your mind. After that it is permanent
          and can’t be edited.
        </Body>
        <ErrorText error={lock.error} />
        <Button
          title="Lock"
          loading={lock.isPending}
          disabled={uploading}
          onPress={() =>
            confirm(
              'Lock this memory?',
              'Its content will be frozen. You can undo within 24 hours; after that it is preserved permanently.',
              'Lock',
              () => lock.mutate(),
            )
          }
        />
      </Card>

      <ErrorText error={discard.error} />
      <Button
        title="Discard draft"
        variant="danger"
        onPress={() => confirm('Discard this draft?', 'It has not been locked, so it will be deleted.', 'Discard', () => discard.mutate())}
      />
    </View>
  );
}

function SealingPanel({ memory, isCreator, onUpdated }: { memory: Memory; isCreator: boolean; onUpdated: (m: Memory) => void }) {
  const t = useTheme();
  const cancel = useMutation({ mutationFn: () => api.cancelLock(memory.id), onSuccess: onUpdated });
  return (
    <Card style={{ borderColor: t.sealing }}>
      <Body style={{ fontWeight: '600' }}>Sealing</Body>
      <Body muted>
        The content is frozen. It becomes permanently locked {timeUntil(memory.sealingUntil!)} (
        {formatDateTime(memory.sealingUntil)}).
      </Body>
      {isCreator ? (
        <>
          <ErrorText error={cancel.error} />
          <Button title="Undo lock" variant="secondary" onPress={() => cancel.mutate()} loading={cancel.isPending} />
        </>
      ) : null}
    </Card>
  );
}

function LockedPanel({ memory, isCreator, onUpdated }: { memory: Memory; isCreator: boolean; onUpdated: (m: Memory) => void }) {
  const queryClient = useQueryClient();
  const t = useTheme();
  const [reflection, setReflection] = useState('');
  const [showProof, setShowProof] = useState(false);
  const reflections = useQuery({ queryKey: ['reflections', memory.id], queryFn: () => api.reflections(memory.id) });
  const integrity = useQuery({
    queryKey: ['integrity', memory.id],
    queryFn: () => api.integrity(memory.id),
    enabled: showProof,
  });
  const addReflection = useMutation({
    mutationFn: () => api.addReflection(memory.id, reflection.trim()),
    onSuccess: () => {
      setReflection('');
      queryClient.invalidateQueries({ queryKey: ['reflections', memory.id] });
    },
  });
  const archive = useMutation({
    mutationFn: () => api.archive(memory.id, memory.state !== 'ARCHIVED'),
    onSuccess: onUpdated,
  });

  return (
    <View style={{ gap: space.lg }}>
      <Body muted>Locked {formatDate(memory.lockedAt)}</Body>
      <Button
        title="Relive"
        onPress={() => router.push({ pathname: '/memory/[id]/relive', params: { id: memory.id } })}
      />

      <View style={{ gap: space.sm }}>
        <Label>Reflections</Label>
        <Body muted>The original stays sealed. Reflections add to it over time.</Body>
        {reflections.data?.map((r) => (
          <Card key={r.id}>
            <Body muted style={{ fontSize: 13 }}>
              {formatDate(r.createdAt)}
            </Body>
            <Body>{r.body}</Body>
          </Card>
        ))}
        <Field
          label="Add a reflection"
          placeholder="Reading this again…"
          value={reflection}
          onChangeText={setReflection}
          multiline
          maxLength={10000}
        />
        <ErrorText error={addReflection.error} />
        <Button
          title="Add reflection"
          variant="secondary"
          onPress={() => addReflection.mutate()}
          loading={addReflection.isPending}
          disabled={!reflection.trim()}
        />
      </View>

      <View style={{ gap: space.sm }}>
        <Label>Proof of preservation</Label>
        {!showProof ? (
          <Button title="Verify integrity" variant="secondary" onPress={() => setShowProof(true)} />
        ) : integrity.isPending ? (
          <Loading />
        ) : integrity.data ? (
          <Card style={{ borderColor: integrity.data.intact ? t.locked : t.danger }}>
            <Body style={{ fontWeight: '600', color: integrity.data.intact ? t.locked : t.danger }}>
              {integrity.data.intact
                ? `Verified intact · ${integrity.data.daysPreserved} days preserved · 0 changes`
                : 'Integrity check failed'}
            </Body>
            <Body muted style={{ fontSize: 12 }}>
              {integrity.data.assetsChecked} media file{integrity.data.assetsChecked === 1 ? '' : 's'} checked
            </Body>
            <Body muted style={{ fontSize: 11, fontFamily: 'monospace' }}>
              SHA-256 {integrity.data.sealedHash}
            </Body>
          </Card>
        ) : (
          <ErrorText error={integrity.error} />
        )}
      </View>

      {isCreator ? (
        <View style={{ gap: space.sm }}>
          <ErrorText error={archive.error} />
          <Button
            title={memory.state === 'ARCHIVED' ? 'Move back to timeline' : 'Archive'}
            variant="secondary"
            onPress={() => archive.mutate()}
            loading={archive.isPending}
          />
          <Button
            title="Request deletion"
            variant="secondary"
            onPress={() => router.push({ pathname: '/memory/[id]/delete', params: { id: memory.id } })}
          />
        </View>
      ) : null}
    </View>
  );
}
