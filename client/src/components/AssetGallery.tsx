import { useQuery } from '@tanstack/react-query';
import { Image, Linking, Pressable, StyleSheet, Text, View } from 'react-native';

import { api } from '@/api/lock';
import type { Asset } from '@/api/types';
import { radius, space, useTheme } from '@/theme';

import { Body } from './ui';

/** Download URLs are short-lived and only issued after the server checks access, so fetch them per asset. */
function AssetTile({ asset, onRemove }: { asset: Asset; onRemove?: () => void }) {
  const t = useTheme();
  const usable = asset.uploadStatus === 'VERIFIED' || asset.uploadStatus === 'ATTACHED';
  const url = useQuery({
    queryKey: ['asset-url', asset.id],
    queryFn: () => api.downloadUrl(asset.id),
    enabled: usable,
    staleTime: 10 * 60_000, // presigned URLs last 15 minutes
  });

  return (
    <View style={[styles.tile, { backgroundColor: t.surfaceMuted, borderColor: t.border }]}>
      {asset.type === 'PHOTO' && url.data ? (
        <Image source={{ uri: url.data.url }} style={styles.image} accessibilityLabel="Photo" />
      ) : (
        <Pressable
          style={styles.placeholder}
          disabled={!url.data}
          onPress={() => url.data && Linking.openURL(url.data.url)}>
          <Text style={{ color: t.textMuted, fontWeight: '600' }}>
            {usable ? (asset.type === 'VIDEO' ? '▶ Video' : '♪ Audio') : asset.uploadStatus.toLowerCase().replace('_', ' ')}
          </Text>
        </Pressable>
      )}
      {onRemove ? (
        <Pressable onPress={onRemove} style={[styles.remove, { backgroundColor: t.background }]} accessibilityLabel="Remove">
          <Text style={{ color: t.text, fontWeight: '700' }}>✕</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

export function AssetGallery({ assets, onRemove }: { assets: Asset[]; onRemove?: (asset: Asset) => void }) {
  if (assets.length === 0) return null;
  return (
    <View style={{ gap: space.sm }}>
      <View style={styles.grid}>
        {assets.map((a) => (
          <AssetTile key={a.id} asset={a} onRemove={onRemove ? () => onRemove(a) : undefined} />
        ))}
      </View>
      <Body muted style={{ fontSize: 12 }}>
        {assets.length} item{assets.length === 1 ? '' : 's'}
      </Body>
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tile: { width: 104, height: 104, borderRadius: radius.md, borderWidth: 1, overflow: 'hidden' },
  image: { width: '100%', height: '100%' },
  placeholder: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xs },
  remove: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
