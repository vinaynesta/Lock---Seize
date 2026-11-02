import { useMutation, useQuery } from '@tanstack/react-query';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';

import { api } from '@/api/lock';
import { Body, Button, Card, ErrorText, Label, Screen } from '@/components/ui';
import { approve } from '@/lib/deviceApproval';
import { unlockedSecretKey } from '@/lib/sealedVault';
import { space } from '@/theme';

/**
 * The unlocked side of device approval (roadmap 3.6e): scan the code the new device shows. The keys
 * are only shared if the code matches the request the server lists, so nobody can slip in their own.
 */
export default function ApproveDevice() {
  const [permission, requestPermission] = useCameraPermissions();
  const [scanned, setScanned] = useState<string | null>(null);
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const pending = useQuery({ queryKey: ['deviceApprovals'], queryFn: api.deviceApprovals, refetchInterval: 5000 });
  const run = useMutation({ mutationFn: (data: string) => approve(data, unlockedSecretKey(), me.data!.id) });

  const onScan = (data: string) => {
    if (scanned || run.isPending) return;
    setScanned(data);
    run.mutate(data);
  };

  if (run.isSuccess) {
    return (
      <Screen>
        <Card>
          <Body style={{ fontWeight: '600' }}>Device approved</Body>
          <Body muted>{run.data.deviceName ?? 'The new device'} can now open your sealed vaults.</Body>
        </Card>
        <Button title="Done" onPress={() => router.back()} />
      </Screen>
    );
  }

  return (
    <Screen>
      <Body>On the new device, open Settings → Sealed Vault keys → “Use another device”, then scan the code it shows.</Body>
      {pending.data && pending.data.length > 0 ? (
        <View style={{ gap: space.xs }}>
          <Label>Waiting for approval</Label>
          {pending.data.map((p) => (
            <Body key={p.id} muted style={{ fontSize: 13 }}>
              {p.deviceName ?? 'A device'} · code ends {p.fingerprint.slice(-9)}
            </Body>
          ))}
        </View>
      ) : null}
      {!permission?.granted ? (
        <Button title="Allow camera to scan" onPress={() => void requestPermission()} />
      ) : (
        <View style={{ height: 300, borderRadius: 12, overflow: 'hidden' }}>
          <CameraView
            style={{ flex: 1 }}
            barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
            onBarcodeScanned={scanned ? undefined : ({ data }) => onScan(data)}
          />
        </View>
      )}
      <ErrorText error={run.error} />
      {run.isError ? <Button title="Scan again" variant="secondary" onPress={() => { setScanned(null); run.reset(); }} /> : null}
    </Screen>
  );
}
