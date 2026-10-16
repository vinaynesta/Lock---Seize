import { useQuery } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { View } from 'react-native';

import { api } from '@/api/lock';
import { AssetGallery } from '@/components/AssetGallery';
import { MemoryCard } from '@/components/MemoryCard';
import { Body, Card, ErrorText, Label, Loading, Screen, Title } from '@/components/ui';
import { formatDate, timeAgo } from '@/lib/format';
import { space, useTheme } from '@/theme';

/**
 * Relive is a product experience, not just a GET (design doc §34): time since locking, then the
 * original photo, thought, feeling and place, then reflections and nearby memories.
 */
export default function Relive() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const t = useTheme();
  // Not cached: each visit is a deliberate "relive" and is recorded server-side.
  const relive = useQuery({ queryKey: ['relive', id], queryFn: () => api.relive(id), gcTime: 0, staleTime: 0 });

  if (relive.isPending) return <Loading />;
  if (relive.error) {
    return (
      <Screen>
        <ErrorText error={relive.error} />
      </Screen>
    );
  }

  const { memory: m, daysSinceLocked, reflections, samePeriod } = relive.data;

  return (
    <Screen>
      <Body muted style={{ textAlign: 'center', fontStyle: 'italic' }}>
        You locked this memory {timeAgo(daysSinceLocked)}.
      </Body>

      <AssetGallery assets={m.assets} />

      <View style={{ gap: space.sm }}>
        <Title>{m.title}</Title>
        <Body muted>
          {formatDate(m.occurredAt)}
          {m.location ? ` · ${m.location}` : ''}
        </Body>
      </View>

      {m.thought ? <Body style={{ fontSize: 18, lineHeight: 28 }}>{m.thought}</Body> : null}
      {m.feeling ? (
        <Card>
          <Label>How it felt</Label>
          <Body style={{ fontSize: 18, color: t.accent }}>{m.feeling}</Body>
        </Card>
      ) : null}

      {reflections.length > 0 ? (
        <View style={{ gap: space.sm }}>
          <Label>Reflections since</Label>
          {reflections.map((r) => (
            <Card key={r.id}>
              <Body muted style={{ fontSize: 13 }}>
                {formatDate(r.createdAt)}
              </Body>
              <Body>{r.body}</Body>
            </Card>
          ))}
        </View>
      ) : null}

      {samePeriod.length > 0 ? (
        <View style={{ gap: space.sm }}>
          <Label>From around the same time</Label>
          {samePeriod.map((s) => (
            <MemoryCard key={s.id} memory={s} />
          ))}
        </View>
      ) : null}
    </Screen>
  );
}
