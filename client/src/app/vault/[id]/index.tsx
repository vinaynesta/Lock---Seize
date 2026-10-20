import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link, router, Stack, useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { api } from '@/api/lock';
import type { Memory, MemoryState } from '@/api/types';
import { MemoryCard } from '@/components/MemoryCard';
import { Button, Empty, ErrorText, Label, Loading, Screen } from '@/components/ui';
import { radius, space, useTheme } from '@/theme';

const FILTERS: { label: string; state?: MemoryState }[] = [
  { label: 'All' },
  { label: 'Locked', state: 'LOCKED' },
  { label: 'Sealing', state: 'SEALING' },
  { label: 'Drafts', state: 'DRAFT' },
  { label: 'Archived', state: 'ARCHIVED' },
];

const PAGE_SIZE = 50;

/** Groups the timeline by year, newest first (design doc §33). */
function byYear(memories: Memory[]): [string, Memory[]][] {
  const groups = new Map<string, Memory[]>();
  for (const m of memories) {
    const year = String(new Date(m.occurredAt).getFullYear());
    groups.set(year, [...(groups.get(year) ?? []), m]);
  }
  return [...groups.entries()];
}

export default function VaultTimeline() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const t = useTheme();
  const [filter, setFilter] = useState<MemoryState | undefined>();
  const vault = useQuery({ queryKey: ['vault', id], queryFn: () => api.vault(id) });
  const timeline = useInfiniteQuery({
    queryKey: ['timeline', id, filter],
    queryFn: ({ pageParam }) => api.timeline(id, { state: filter, limit: PAGE_SIZE, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
  });
  const memories = useMemo(() => timeline.data?.pages.flatMap((p) => p.memories), [timeline.data]);

  return (
    <Screen>
      <Stack.Screen
        options={{
          title: vault.data?.name ?? 'Vault',
          headerRight: () =>
            vault.data?.type === 'SHARED' ? (
              <Link href={{ pathname: '/vault/[id]/members', params: { id } }} asChild>
                <Pressable hitSlop={12}>
                  <Text style={{ color: t.accent, fontSize: 16 }}>Members</Text>
                </Pressable>
              </Link>
            ) : null,
        }}
      />

      <Button
        title="Capture a memory"
        onPress={() => router.push({ pathname: '/vault/[id]/compose', params: { id } })}
      />

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
        {FILTERS.map((f) => {
          const active = f.state === filter;
          return (
            <Pressable
              key={f.label}
              onPress={() => setFilter(f.state)}
              accessibilityRole="button"
              accessibilityState={{ selected: active }}
              style={{
                paddingHorizontal: space.md,
                paddingVertical: space.xs + 2,
                borderRadius: radius.lg,
                borderWidth: 1,
                borderColor: active ? t.accent : t.border,
                backgroundColor: active ? t.accent : 'transparent',
              }}>
              <Text style={{ color: active ? t.accentText : t.text, fontWeight: '600' }}>{f.label}</Text>
            </Pressable>
          );
        })}
      </View>

      {timeline.isPending ? <Loading /> : null}
      <ErrorText error={timeline.error} />
      {memories && memories.length === 0 ? (
        <Empty title="Nothing here yet" hint="Capture a moment, then lock it to preserve it." />
      ) : null}
      {memories
        ? byYear(memories).map(([year, inYear]) => (
            <View key={year} style={{ gap: space.sm }}>
              <Label>{year}</Label>
              {inYear.map((m) => (
                <MemoryCard key={m.id} memory={m} />
              ))}
            </View>
          ))
        : null}
      {timeline.hasNextPage ? (
        <Button
          title="Load older memories"
          variant="secondary"
          onPress={() => void timeline.fetchNextPage()}
          loading={timeline.isFetchingNextPage}
        />
      ) : null}
    </Screen>
  );
}
