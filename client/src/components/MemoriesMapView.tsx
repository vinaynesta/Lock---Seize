import { router } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Switch, View } from 'react-native';

import type { Coordinates, Memory } from '@/api/types';
import { formatDate } from '@/lib/format';
import { formatPin, geocodingSupported, lookUpPlace } from '@/lib/geo';
import { radius, space, useTheme } from '@/theme';

import { MemoryMap, mapSupported, type MapPoint } from './MemoryMap';
import { Body, Empty, Group, Row, Section } from './ui';

/** Looked-up "Where" texts for this session only (never stored): text → position, or null if not found. */
const placeCache = new Map<string, Coordinates | null>();

/**
 * Memories on a map with a place-grouped list under it (roadmap 5.1; the list is the whole view on web).
 * Used for one vault and for all vaults (5.2): `vaultNames` labels each row with its vault when given.
 * Pinned memories always show; unpinned ones with a "Where" text can be placed by the phone's geocoder
 * when the user switches it on (this session only, never stored). Closed time capsules never show.
 */
export function MemoriesMapView({ memories, vaultNames }: { memories: Memory[]; vaultNames?: Record<string, string> }) {
  const t = useTheme();
  const [byText, setByText] = useState(false);
  const [placed, setPlaced] = useState<Record<string, Coordinates | null>>({});
  const visible = useMemo(() => memories.filter((m) => !m.sealedCapsule && m.title !== null), [memories]);
  const unpinnedWithText = useMemo(() => visible.filter((m) => !m.coordinates && m.location?.trim()), [visible]);

  // Look up "Where" texts one at a time (platform geocoders rate-limit bursts), only when switched on.
  useEffect(() => {
    if (!byText) return;
    let live = true;
    void (async () => {
      for (const m of unpinnedWithText) {
        const text = m.location!.trim();
        if (!placeCache.has(text)) placeCache.set(text, await lookUpPlace(text));
        if (!live) return;
        setPlaced((p) => ({ ...p, [m.id]: placeCache.get(text) ?? null }));
      }
    })();
    return () => {
      live = false;
    };
  }, [byText, unpinnedWithText]);

  const points: MapPoint[] = useMemo(() => {
    const out: MapPoint[] = [];
    for (const m of visible) {
      if (m.coordinates) out.push({ id: m.id, title: m.title!, coordinates: m.coordinates });
      else if (byText && placed[m.id]) out.push({ id: m.id, title: m.title!, coordinates: placed[m.id]!, approximate: true });
    }
    return out;
  }, [visible, placed, byText]);

  /** The list under the map (and the whole view on web): grouped by place, biggest groups first. */
  const groups = useMemo(() => {
    const byPlace = new Map<string, Memory[]>();
    for (const p of points) {
      const m = visible.find((x) => x.id === p.id)!;
      const key = m.location?.trim() || formatPin(p.coordinates);
      byPlace.set(key, [...(byPlace.get(key) ?? []), m]);
    }
    return [...byPlace.entries()].sort((a, b) => b[1].length - a[1].length);
  }, [points, visible]);

  const open = (memoryId: string) => router.push({ pathname: '/memory/[id]', params: { id: memoryId } });
  const unplaced = visible.length - points.length;

  return (
    <>
      {mapSupported && points.length > 0 ? (
        <View style={[styles.mapFrame, { borderColor: t.border, backgroundColor: t.surfaceMuted }]}>
          <MemoryMap points={points} onOpen={open} style={StyleSheet.absoluteFill} />
        </View>
      ) : null}

      {geocodingSupported && unpinnedWithText.length > 0 ? (
        <View style={styles.switchRow}>
          <View style={{ flex: 1, gap: 2 }}>
            <Body style={{ fontWeight: '600' }}>Place by “Where”</Body>
            <Body muted style={{ fontSize: 13 }}>
              {unpinnedWithText.length} unpinned memor{unpinnedWithText.length === 1 ? 'y has' : 'ies have'} a place name.
              Your phone looks them up with its maps provider; nothing is saved.
            </Body>
          </View>
          <Switch value={byText} onValueChange={setByText} trackColor={{ true: t.accent }} />
        </View>
      ) : null}

      {points.length === 0 ? (
        <Empty
          title="Nothing on the map yet"
          hint="Pin a memory while it’s a draft: “Use my location”, “Find ‘Where’ on the map” or “Pick on the map”."
        />
      ) : (
        groups.map(([place, inPlace]) => (
          <Section key={place} title={place}>
            <Group>
              {inPlace.map((m) => (
                <Row
                  key={m.id}
                  title={m.title!}
                  subtitle={[formatDate(m.occurredAt), vaultNames?.[m.vaultId], m.coordinates ? null : 'placed from “Where”']
                    .filter(Boolean)
                    .join(' · ')}
                  onPress={() => open(m.id)}
                />
              ))}
            </Group>
          </Section>
        ))
      )}

      {unplaced > 0 && points.length > 0 ? (
        <Body muted style={{ fontSize: 13, textAlign: 'center' }}>
          {unplaced} memor{unplaced === 1 ? 'y isn’t' : 'ies aren’t'} on the map.
        </Body>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  mapFrame: { height: 360, borderRadius: radius.lg, overflow: 'hidden', borderWidth: StyleSheet.hairlineWidth },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: space.md },
});
