import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';

import { MemoriesMapView } from '@/components/MemoriesMapView';
import { Body, ErrorText, Group, Loading, Row, Screen, Section } from '@/components/ui';
import { allVaultsMap } from '@/lib/mapData';

/** Every vault's memories on one map (roadmap 5.2). Sealed vaults appear only if this device can open them. */
export default function AllMemoriesMap() {
  const data = useQuery({ queryKey: ['map', 'all'], queryFn: allVaultsMap });

  return (
    <Screen>
      {data.isPending ? <Loading /> : null}
      <ErrorText error={data.error} />
      {data.data ? (
        <>
          <MemoriesMapView memories={data.data.memories} vaultNames={data.data.vaultNames} />
          {data.data.skipped.length > 0 ? (
            <Section title="Not included">
              <Body muted style={{ fontSize: 14 }}>
                These sealed vaults are end-to-end encrypted. Unlock your Sealed Vault keys on this device to include them.
              </Body>
              <Group>
                {data.data.skipped.map((v) => (
                  <Row key={v.id} title={v.name} subtitle="Sealed · unlock to include" onPress={() => router.push('/sealed-keys')} />
                ))}
              </Group>
            </Section>
          ) : null}
        </>
      ) : null}
    </Screen>
  );
}
