import type { Coordinates } from '@/api/types';

/** No native map on web (roadmap 5.2): the form hides "Pick on the map" there. */
export function PinPicker(_props: {
  visible: boolean;
  initial: Coordinates | null;
  onPick: (c: Coordinates) => void;
  onClose: () => void;
}) {
  return null;
}
