/**
 * Map positions for memories (roadmap 5.1). Positions come from the device: the current GPS fix, or the
 * phone's own geocoder turning a memory's "Where" text into coordinates. Both run on the device; a
 * position is then stored encrypted with the memory like any other field.
 *
 * Geocoding uses the platform geocoder (Apple on iOS, Google Play services on Android), so the text
 * looked up goes to that provider. The app only does it when the user asks (a button in the form, or
 * the "place by 'Where'" switch on the map).
 */
import * as Location from 'expo-location';
import { Platform } from 'react-native';

import type { Coordinates } from '@/api/types';

/** The platform geocoder exists on iOS and Android only. */
export const geocodingSupported = Platform.OS === 'ios' || Platform.OS === 'android';

export class LocationUnavailable extends Error {}

/** The device's current position, asking for "while using the app" permission the first time. */
export async function currentPosition(): Promise<Coordinates> {
  const permission = await Location.requestForegroundPermissionsAsync();
  if (!permission.granted) throw new LocationUnavailable('Lock needs location access to pin where you are.');
  const fix = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
  return round({ latitude: fix.coords.latitude, longitude: fix.coords.longitude });
}

/** Turns a place description ("Miradouro da Graça, Lisbon") into coordinates, or null if not found. */
export async function lookUpPlace(text: string): Promise<Coordinates | null> {
  if (!geocodingSupported || !text.trim()) return null;
  try {
    const [first] = await Location.geocodeAsync(text.trim());
    return first ? round({ latitude: first.latitude, longitude: first.longitude }) : null;
  } catch {
    return null; // offline or rate-limited by the platform: just not placed
  }
}

/** 6 decimals (about 10 cm): what is stored. */
function round(c: Coordinates): Coordinates {
  return { latitude: Number(c.latitude.toFixed(6)), longitude: Number(c.longitude.toFixed(6)) };
}

/** "38.7139° N, 9.1333° W". */
export function formatPin(c: Coordinates): string {
  const lat = `${Math.abs(c.latitude).toFixed(4)}° ${c.latitude >= 0 ? 'N' : 'S'}`;
  const lng = `${Math.abs(c.longitude).toFixed(4)}° ${c.longitude >= 0 ? 'E' : 'W'}`;
  return `${lat}, ${lng}`;
}

/** A camera that fits every point: their centre, zoomed out by how far apart they are. */
export function cameraFor(points: Coordinates[]): { coordinates: Coordinates; zoom: number } {
  if (points.length === 0) return { coordinates: { latitude: 20, longitude: 0 }, zoom: 1 };
  const lats = points.map((p) => p.latitude);
  const lngs = points.map((p) => p.longitude);
  const center = {
    latitude: (Math.min(...lats) + Math.max(...lats)) / 2,
    longitude: (Math.min(...lngs) + Math.max(...lngs)) / 2,
  };
  const span = Math.max(Math.max(...lats) - Math.min(...lats), Math.max(...lngs) - Math.min(...lngs), 0.01);
  // Each zoom level halves the visible span; 360° fills the screen at zoom 1.
  const zoom = Math.max(1, Math.min(15, Math.log2(360 / span) - 0.5));
  return { coordinates: center, zoom };
}

export interface Cluster<T extends { id: string; coordinates: Coordinates }> {
  /** The point's own id for a single point; `cluster:<cell>` for a group. */
  id: string;
  coordinates: Coordinates;
  points: T[];
}

/**
 * Groups pins that would overlap at this zoom (roadmap 5.2) on a grid whose cells halve with each zoom
 * level: about 1/9 of the visible width. A single pin stays itself; a group sits at its members' centre.
 * Deterministic, so markers keep their ids while the map is panned.
 */
export function clusterPoints<T extends { id: string; coordinates: Coordinates }>(points: T[], zoom: number): Cluster<T>[] {
  const cell = 40 / 2 ** Math.max(0, Math.round(zoom));
  const cells = new Map<string, T[]>();
  for (const p of points) {
    const key = `${Math.floor(p.coordinates.latitude / cell)}:${Math.floor(p.coordinates.longitude / cell)}`;
    cells.set(key, [...(cells.get(key) ?? []), p]);
  }
  return [...cells.entries()].map(([key, members]) =>
    members.length === 1
      ? { id: members[0].id, coordinates: members[0].coordinates, points: members }
      : {
          id: `cluster:${key}`,
          coordinates: {
            latitude: members.reduce((s, m) => s + m.coordinates.latitude, 0) / members.length,
            longitude: members.reduce((s, m) => s + m.coordinates.longitude, 0) / members.length,
          },
          points: members,
        },
  );
}
