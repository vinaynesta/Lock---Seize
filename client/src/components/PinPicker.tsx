import { AppleMaps, GoogleMaps } from 'expo-maps';
import { useState } from 'react';
import { Modal, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { Coordinates } from '@/api/types';
import { formatPin } from '@/lib/geo';
import { radius, space, useTheme } from '@/theme';

/**
 * Pick a memory's map position by hand (roadmap 5.2). The pin is the crosshair at the centre of the map:
 * pan the map under it, then "Pin here". This works on every supported OS because it only needs camera
 * movement events; where map taps exist (Android, iOS 18+), tapping a spot moves the map there too.
 */
export function PinPicker({
  visible,
  initial,
  onPick,
  onClose,
}: {
  visible: boolean;
  initial: Coordinates | null;
  onPick: (c: Coordinates) => void;
  onClose: () => void;
}) {
  return (
    <Modal visible={visible} animationType="slide" presentationStyle="fullScreen" onRequestClose={onClose}>
      {visible ? <Picker initial={initial} onPick={onPick} onClose={onClose} /> : null}
    </Modal>
  );
}

function Picker({ initial, onPick, onClose }: { initial: Coordinates | null; onPick: (c: Coordinates) => void; onClose: () => void }) {
  const t = useTheme();
  const [start] = useState(() => ({ coordinates: initial ?? { latitude: 20, longitude: 0 }, zoom: initial ? 15 : 2 }));
  const [camera, setCamera] = useState(start);
  const [center, setCenter] = useState<Coordinates>(start.coordinates);
  const onCameraMove = (e: { coordinates: { latitude?: number; longitude?: number } }) => {
    if (e.coordinates.latitude != null && e.coordinates.longitude != null) {
      setCenter({ latitude: e.coordinates.latitude, longitude: e.coordinates.longitude });
    }
  };
  const onMapClick = (e: { coordinates: { latitude?: number; longitude?: number } }) => {
    if (e.coordinates.latitude == null || e.coordinates.longitude == null) return;
    const c = { latitude: e.coordinates.latitude, longitude: e.coordinates.longitude };
    setCamera({ coordinates: c, zoom: Math.max(camera.zoom, 15) });
    setCenter(c);
  };
  const round = (c: Coordinates) => ({ latitude: Number(c.latitude.toFixed(6)), longitude: Number(c.longitude.toFixed(6)) });

  return (
    <View style={{ flex: 1, backgroundColor: t.background }}>
      {Platform.OS === 'ios' ? (
        <AppleMaps.View style={StyleSheet.absoluteFill} cameraPosition={camera} onCameraMove={onCameraMove} onMapClick={onMapClick} />
      ) : (
        <GoogleMaps.View style={StyleSheet.absoluteFill} cameraPosition={camera} onCameraMove={onCameraMove} onMapClick={onMapClick} />
      )}
      {/* The crosshair marks exactly what "Pin here" saves. */}
      <View pointerEvents="none" style={styles.crosshairWrap}>
        <View style={[styles.crosshairRing, { borderColor: t.accent }]}>
          <View style={[styles.crosshairDot, { backgroundColor: t.accent }]} />
        </View>
      </View>
      <SafeAreaView pointerEvents="box-none" style={styles.chrome} edges={['top', 'bottom']}>
        <View style={[styles.hint, { backgroundColor: t.surface, borderColor: t.border }]}>
          <Text style={{ color: t.text, fontWeight: '600' }}>Move the map to put the spot under the circle</Text>
          <Text style={{ color: t.textMuted, fontSize: 13 }}>{formatPin(center)}</Text>
        </View>
        <View style={styles.actions}>
          <Pressable onPress={onClose} style={[styles.button, { backgroundColor: t.surface, borderColor: t.border }]} accessibilityRole="button">
            <Text style={{ color: t.text, fontWeight: '600' }}>Cancel</Text>
          </Pressable>
          <Pressable
            onPress={() => onPick(round(center))}
            style={[styles.button, { backgroundColor: t.ink, borderColor: t.ink, flex: 1 }]}
            accessibilityRole="button">
            <Text style={{ color: t.inkText, fontWeight: '600' }}>Pin here</Text>
          </Pressable>
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  crosshairWrap: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center' },
  crosshairRing: { width: 28, height: 28, borderRadius: 14, borderWidth: 2.5, alignItems: 'center', justifyContent: 'center' },
  crosshairDot: { width: 6, height: 6, borderRadius: 3 },
  chrome: { flex: 1, justifyContent: 'space-between', padding: space.lg },
  hint: { padding: space.md, borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth, gap: 2 },
  actions: { flexDirection: 'row', gap: space.sm },
  button: { minHeight: 52, paddingHorizontal: space.xl, borderRadius: radius.pill, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
});
