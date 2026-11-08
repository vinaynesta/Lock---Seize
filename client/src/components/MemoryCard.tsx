import { router } from 'expo-router';
import { Text, View } from 'react-native';

import type { Memory } from '@/api/types';
import { formatDate } from '@/lib/format';
import { space, useTheme } from '@/theme';

import { Card, StateBadge } from './ui';

export function MemoryCard({ memory }: { memory: Memory }) {
  const t = useTheme();
  const title = memory.sealedCapsule ? `Opens ${formatDate(memory.revealAt)}` : (memory.title ?? 'Encrypted memory');
  return (
    <Card onPress={() => router.push({ pathname: '/memory/[id]', params: { id: memory.id } })} style={{ gap: space.md }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: space.sm }}>
        <Text style={{ color: t.textMuted, fontSize: 13, fontWeight: '500' }}>{formatDate(memory.occurredAt)}</Text>
        <StateBadge state={memory.state} capsule={memory.sealedCapsule} />
      </View>
      <Text numberOfLines={2} style={{ color: t.text, fontSize: 19, fontWeight: '600', letterSpacing: -0.3, lineHeight: 25 }}>
        {title}
      </Text>
      {memory.feeling && !memory.sealedCapsule ? (
        <Text style={{ color: t.textMuted, fontSize: 15, fontStyle: 'italic' }}>Feeling {memory.feeling}</Text>
      ) : null}
    </Card>
  );
}
