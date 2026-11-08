import type { ViewStyle } from 'react-native';

import type { Coordinates } from '@/api/types';

export interface MapPoint {
  id: string;
  title: string;
  coordinates: Coordinates;
  approximate?: boolean;
}

/** expo-maps has no web build: the map screen shows its list instead (roadmap 5.1). */
export const mapSupported = false;

export function MemoryMap(_props: { points: MapPoint[]; onOpen: (id: string) => void; style?: ViewStyle }) {
  return null;
}
