import { useVideoPlayer, VideoView } from 'expo-video';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { AssetType } from '@/api/types';
import { radius, space } from '@/theme';

/**
 * In-app player for video and audio. Sealed media arrives as a decrypted file in the app cache
 * (`file://`), so it plays without leaving the app or passing plaintext to another app. Server-side
 * media plays its PLAYBACK rendition from a short-lived URL.
 */
export function MediaPlayer({
  uri,
  type,
  onClose,
}: {
  uri: string | null;
  type: AssetType;
  onClose: () => void;
}) {
  return (
    <Modal visible={uri !== null} animationType="fade" presentationStyle="fullScreen" onRequestClose={onClose}>
      <SafeAreaView style={styles.root}>
        {uri ? <Player uri={uri} audio={type === 'AUDIO'} /> : null}
        <Pressable
          onPress={onClose}
          accessibilityRole="button"
          accessibilityLabel="Close player"
          hitSlop={12}
          style={({ pressed }) => [styles.close, pressed && { opacity: 0.6 }]}>
          <Text style={styles.closeText}>Done</Text>
        </Pressable>
      </SafeAreaView>
    </Modal>
  );
}

function Player({ uri, audio }: { uri: string; audio: boolean }) {
  const player = useVideoPlayer({ uri }, (p) => {
    p.loop = false;
    p.play();
  });
  return (
    <View style={styles.stage}>
      {audio ? (
        <Text style={styles.audioMark} accessibilityElementsHidden>
          ♪
        </Text>
      ) : null}
      <VideoView
        player={player}
        nativeControls
        contentFit="contain"
        fullscreenOptions={{ enable: !audio }}
        style={audio ? styles.audioControls : styles.video}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  stage: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  video: { width: '100%', height: '100%' },
  audioMark: { color: 'rgba(255,255,255,0.85)', fontSize: 72, marginBottom: space.xl },
  audioControls: { width: '90%', height: 96 },
  close: {
    position: 'absolute',
    top: space.xxl + space.lg,
    right: space.lg,
    paddingHorizontal: space.lg,
    paddingVertical: space.sm,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(255,255,255,0.16)',
  },
  closeText: { color: '#fff', fontSize: 15, fontWeight: '600' },
});
