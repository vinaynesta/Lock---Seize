import { router } from 'expo-router';
import { View } from 'react-native';

import type { Memory } from '@/api/types';
import { formatDate } from '@/lib/format';
import { space } from '@/theme';

import { Body, Card, StateBadge } from './ui';

export function MemoryCard({ memory }: { memory: Memory }) {
  return (
    <Card onPress={() => router.push({ pathname: '/memory/[id]', params: { id: memory.id } })}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: space.sm }}>
        <Body muted style={{ fontSize: 13 }}>
          {formatDate(memory.occurredAt)}
        </Body>
        <StateBadge state={memory.state} capsule={memory.sealedCapsule} />
      </View>
      <Body style={{ fontSize: 18, fontWeight: '600' }}>
        {memory.sealedCapsule ? `Opens ${formatDate(memory.revealAt)}` : memory.title}
      </Body>
      {memory.feeling && !memory.sealedCapsule ? <Body muted>Feeling {memory.feeling}</Body> : null}
    </Card>
  );
}
