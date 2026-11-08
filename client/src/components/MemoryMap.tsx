import { AppleMaps, GoogleMaps } from 'expo-maps';
import { useMemo, useState } from 'react';
import { Platform, type ViewStyle } from 'react-native';

import type { Coordinates } from '@/api/types';
import { cameraFor, clusterPoints } from '@/lib/geo';
import { useTheme } from '@/theme';

export interface MapPoint {
  id: string;
  title: string;
  coordinates: Coordinates;
  /** Placed from the memory's "Where" text rather than a stored pin. */
  approximate?: boolean;
}

/** Native maps exist on iOS (Apple Maps, iOS 17+) and Android (Google Maps); web has MemoryMap.web.tsx. */
export const mapSupported = Platform.OS === 'ios' || Platform.OS === 'android';

type Camera = { coordinates: Coordinates; zoom: number };

/**
 * Memories as markers (roadmap 5.1). Pins that would overlap are grouped into one marker with a count,
 * reclustered as the zoom changes; tapping a group zooms in on it (5.2). Tapping a single pin opens the
 * memory: on Android always, on iOS from iOS 18 (expo-maps marker taps need it), so screens also list
 * the same memories under the map.
 */
export function MemoryMap({ points, onOpen, style }: { points: MapPoint[]; onOpen: (id: string) => void; style?: ViewStyle }) {
  const t = useTheme();
  const initial = useMemo(() => cameraFor(points.map((p) => p.coordinates)), [points]);
  const [camera, setCamera] = useState<Camera | null>(null); // set only when we move the map ourselves
  const [zoom, setZoom] = useState(Math.round(initial.zoom));
  const clusters = useMemo(() => clusterPoints(points, zoom), [points, zoom]);

  const onCameraMove = (e: { zoom: number }) => {
    const z = Math.round(e.zoom);
    if (z !== zoom) setZoom(z); // recluster only when the integer zoom changes
  };
  const onMarker = (marker: { id?: string }) => {
    const hit = clusters.find((c) => c.id === marker.id);
    if (!hit) return;
    if (hit.points.length === 1) onOpen(hit.points[0].id);
    else setCamera({ coordinates: hit.coordinates, zoom: Math.min(18, zoom + 2) });
  };
  const titleOf = (c: (typeof clusters)[number]) =>
    c.points.length === 1 ? c.points[0].title : `${c.points.length} memories`;
  const cameraPosition = camera ?? initial;

  if (Platform.OS === 'ios') {
    return (
      <AppleMaps.View
        style={style}
        cameraPosition={cameraPosition}
        markers={clusters.map((c) => ({
          id: c.id,
          coordinates: c.coordinates,
          title: titleOf(c),
          tintColor: c.points.length === 1 && c.points[0].approximate ? t.draft : t.accent,
          systemImage: c.points.length > 1 ? 'square.stack.fill' : undefined,
        }))}
        onMarkerClick={onMarker}
        onCameraMove={onCameraMove}
      />
    );
  }
  if (Platform.OS === 'android') {
    return (
      <GoogleMaps.View
        style={style}
        cameraPosition={cameraPosition}
        markers={clusters.map((c) => ({
          id: c.id,
          coordinates: c.coordinates,
          title: titleOf(c),
          snippet:
            c.points.length > 1
              ? 'Tap to zoom in'
              : c.points[0].approximate
                ? 'Placed from its “Where” text'
                : undefined,
        }))}
        onMarkerClick={onMarker}
        onCameraMove={onCameraMove}
      />
    );
  }
  return null;
}
