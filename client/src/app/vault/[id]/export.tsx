import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Linking, View } from 'react-native';

import { ApiError } from '@/api/client';
import { api } from '@/api/lock';
import type { Vault, VaultExport } from '@/api/types';
import { SealedGate } from '@/components/SealedGate';
import { exportSealedVault, type ExportProgress } from '@/lib/sealedExport';
import { useSealedVault } from '@/lib/useSealed';
import { MfaCodeField, useMfaEnabled } from '@/components/MfaCodeField';
import { Body, Button, Card, ErrorText, Label, Loading, Screen } from '@/components/ui';
import { formatBytes, formatDateTime } from '@/lib/format';
import { space, useTheme } from '@/theme';

/**
 * Export anytime: a ZIP with every memory you can see, its reflections, proof-of-preservation hashes
 * and the original photos and videos. It is built in the background and kept for 7 days.
 */
export default function ExportVault() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  if (vault.data?.encryption === 'SEALED') return <SealedExport vault={vault.data} />;
  return <ServerExport id={id} />;
}

/**
 * Sealed vault (roadmap 3.6f): the server can't decrypt it, so this device builds the same ZIP and
 * hands it to the share sheet. Nothing is uploaded; the plaintext ZIP is deleted afterwards.
 */
function SealedExport({ vault }: { vault: Vault }) {
  const sealed = useSealedVault(vault);
  const [progress, setProgress] = useState<ExportProgress | null>(null);
  const run = useMutation({ mutationFn: () => exportSealedVault(vault, setProgress) });
  if (sealed.status !== 'ready') {
    return (
      <Screen>
        <SealedGate state={sealed} />
      </Screen>
    );
  }
  return (
    <Screen>
      <Body>
        This vault is end-to-end encrypted, so the export is built on this device: every memory, reflection and original
        photo or video, decrypted, in one ZIP you can save or share. Keep the app open until it’s done.
      </Body>
      <Body muted style={{ fontSize: 13 }}>
        The ZIP is readable by anyone who gets it. Store it somewhere safe.
      </Body>
      {progress ? (
        <Body muted>
          {progress.memories} memories · {progress.files} files
        </Body>
      ) : null}
      <ErrorText error={run.error} />
      {run.isSuccess ? <Body>Export finished.</Body> : null}
      <Button title="Export on this device" onPress={() => run.mutate()} loading={run.isPending} />
    </Screen>
  );
}

function ServerExport({ id }: { id: string }) {
  const queryClient = useQueryClient();
  const [code, setCode] = useState('');
  const mfaOn = useMfaEnabled();
  const exports = useQuery({
    queryKey: ['exports', id],
    queryFn: () => api.exports(id),
    refetchInterval: (q) => (q.state.data?.some((e) => e.status === 'QUEUED') ? 3000 : false),
  });
  const request = useMutation({
    mutationFn: () => api.requestExport(id, code),
    onSuccess: () => {
      setCode('');
      return queryClient.invalidateQueries({ queryKey: ['exports', id] });
    },
  });
  const download = useMutation({
    mutationFn: async (exportId: string) => Linking.openURL((await api.exportDownloadUrl(exportId)).url),
  });

  const inProgress = exports.data?.some((e) => e.status === 'QUEUED') ?? false;
  const alreadyRunning = request.error instanceof ApiError && request.error.code === 'EXPORT_IN_PROGRESS';

  return (
    <Screen>
      <Body>Download everything in this vault as a ZIP: your memories, reflections and original photos and videos.</Body>
      <Body muted>
        It includes a manifest with each memory’s proof-of-preservation hash and each file’s SHA-256, so you can check
        nothing changed. The download link works for 7 days.
      </Body>
      {inProgress || alreadyRunning ? null : <MfaCodeField value={code} onChangeText={setCode} />}
      <ErrorText error={alreadyRunning ? null : request.error} />
      <Button
        title={inProgress || alreadyRunning ? 'Preparing your export…' : 'Prepare export'}
        onPress={() => request.mutate()}
        loading={request.isPending}
        disabled={inProgress || alreadyRunning || (mfaOn && !code.trim())}
      />

      {exports.isPending ? <Loading /> : null}
      <ErrorText error={exports.error ?? download.error} />
      {exports.data && exports.data.length > 0 ? (
        <View style={{ gap: space.sm }}>
          <Label>Your exports</Label>
          {exports.data.map((e) => (
            <ExportRow key={e.id} export={e} onDownload={() => download.mutate(e.id)} downloading={download.isPending} />
          ))}
        </View>
      ) : null}
    </Screen>
  );
}

function ExportRow({ export: e, onDownload, downloading }: { export: VaultExport; onDownload: () => void; downloading: boolean }) {
  const t = useTheme();
  return (
    <Card style={e.status === 'FAILED' ? { borderColor: t.danger } : undefined}>
      <Body style={{ fontWeight: '600' }}>{formatDateTime(e.requestedAt)}</Body>
      {e.status === 'QUEUED' ? <Body muted>Preparing…</Body> : null}
      {e.status === 'READY' ? (
        <>
          <Body muted>
            {e.memoryCount} memor{e.memoryCount === 1 ? 'y' : 'ies'} · {e.assetCount} file{e.assetCount === 1 ? '' : 's'} ·{' '}
            {formatBytes(e.sizeBytes)} · available until {formatDateTime(e.expiresAt)}
          </Body>
          <Button title="Download ZIP" variant="secondary" onPress={onDownload} loading={downloading} />
        </>
      ) : null}
      {e.status === 'FAILED' ? <Body style={{ color: t.danger }}>{e.error ?? 'The export failed.'}</Body> : null}
      {e.status === 'EXPIRED' ? <Body muted>Expired</Body> : null}
    </Card>
  );
}
