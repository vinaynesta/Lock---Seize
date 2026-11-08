import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { router, Stack, useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { View } from 'react-native';

import { api } from '@/api/lock';
import type { Memory, MemoryState } from '@/api/types';
import { MemoryCard } from '@/components/MemoryCard';
import { PendingSync } from '@/components/PendingSync';
import { SealedGate } from '@/components/SealedGate';
import { searchSealed } from '@/lib/sealedVault';
import { useSealedVault } from '@/lib/useSealed';
import { Body, Button, Chip, Empty, ErrorText, Field, Label, Loading, Screen, TextLink } from '@/components/ui';
import { space } from '@/theme';

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
  const [filter, setFilter] = useState<MemoryState | undefined>();
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  useEffect(() => {
    const handle = setTimeout(() => setDebounced(query.trim()), 300);
    return () => clearTimeout(handle);
  }, [query]);
  const vault = useQuery({
    queryKey: ['vault', id],
    queryFn: () => api.vault(id),
  });
  const sealed = useSealedVault(vault.data);
  const isSealed = vault.data?.encryption === 'SEALED';
  const search = useQuery({
    queryKey: ['search', id, debounced, sealed.status],
    // Sealed vaults (3.6f): the query is turned into blind tokens on this device; the words never leave it.
    queryFn: () => (isSealed ? searchSealed(id, debounced) : api.search(id, debounced)),
    enabled: debounced.length > 0 && (!isSealed || sealed.status === 'ready'),
  });
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
          headerRight: () => (
            <View style={{ flexDirection: 'row', gap: space.lg }}>
              <TextLink title="Map" onPress={() => router.push({ pathname: '/vault/[id]/map', params: { id } })} />
              {vault.data?.type === 'SHARED' ? (
                <TextLink title="Members" onPress={() => router.push({ pathname: '/vault/[id]/members', params: { id } })} />
              ) : null}
            </View>
          ),
        }}
      />

      <SealedGate state={sealed} />
      {isSealed && sealed.status === 'ready' ? <Body muted style={{ fontSize: 14 }}>End-to-end encrypted. Only members’ devices can read it.</Body> : null}

      <Button
        title="Capture a memory"
        onPress={() => router.push({ pathname: '/vault/[id]/compose', params: { id } })}
        disabled={isSealed && sealed.status !== 'ready'}
      />

      <PendingSync vaultId={id} />

      {isSealed && sealed.status !== 'ready' ? null : (
      <Field
        label="Search"
        value={query}
        onChangeText={setQuery}
        placeholder="Titles, thoughts, feelings, places"
        autoCapitalize="none"
        autoCorrect={false}
        returnKeyType="search"
        clearButtonMode="while-editing"
      />
      )}

      {debounced ? (
        <View style={{ gap: space.sm }}>
          {search.isPending ? <Loading /> : null}
          <ErrorText error={search.error} />
          {search.data && search.data.length === 0 ? <Body muted>No memories match “{debounced}”.</Body> : null}
          {search.data?.map((m) => (
            <MemoryCard key={m.id} memory={m} />
          ))}
        </View>
      ) : null}

      {debounced ? null : (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
          {FILTERS.map((f) => (
            <Chip key={f.label} label={f.label} active={f.state === filter} onPress={() => setFilter(f.state)} />
          ))}
        </View>
      )}

      {debounced ? null : (
        <>
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
              variant="ghost"
              onPress={() => void timeline.fetchNextPage()}
              loading={timeline.isFetchingNextPage}
            />
          ) : null}
        </>
      )}

      {isSealed && sealed.status !== 'ready' ? null : (
        <Button
          title="Export this vault"
          variant="ghost"
          onPress={() => router.push({ pathname: '/vault/[id]/export', params: { id } })}
        />
      )}
    </Screen>
  );
}
