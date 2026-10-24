import { useQuery } from '@tanstack/react-query';
import { Image, Linking, Pressable, StyleSheet, Text, View } from 'react-native';

import { api } from '@/api/lock';
import type { Asset, AssetVariant, UploadStatus } from '@/api/types';
import { radius, space, useTheme } from '@/theme';

import { Body } from './ui';

const STATUS_LABEL: Partial<Record<UploadStatus, string>> = {
  INITIATED: 'uploading',
  PENDING_SCAN: 'safety check…',
  VERIFICATION_FAILED: 'upload failed',
  REJECTED: 'blocked',
  EXPIRED: 'expired',
};

/**
 * Download URLs are short-lived and only issued after the server checks access, so fetch them per
 * asset. Grids ask for the 256 px thumbnail; the server falls back to the original when none exists.
 */
function AssetTile({ asset, variant, onRemove }: { asset: Asset; variant: AssetVariant; onRemove?: () => void }) {
  const t = useTheme();
  const usable = asset.uploadStatus === 'VERIFIED' || asset.uploadStatus === 'ATTACHED';
  // Photos and videos both get stills; a video tile shows its THUMB and plays its PLAYBACK rendition.
  const wanted = asset.type !== 'AUDIO' && asset.variants.includes(variant) ? variant : undefined;
  const url = useQuery({
    queryKey: ['asset-url', asset.id, wanted ?? 'original'],
    queryFn: () => api.downloadUrl(asset.id, wanted),
    enabled: usable,
    staleTime: 10 * 60_000, // presigned URLs last 15 minutes
  });
  const rejected = asset.uploadStatus === 'REJECTED';

  return (
    <View
      style={[styles.tile, { backgroundColor: t.surfaceMuted, borderColor: rejected ? t.danger : t.border }]}
      accessibilityLabel={rejected ? 'Blocked by the safety check' : undefined}>
      {wanted && url.data ? (
        <Pressable
          style={styles.image}
          onPress={() => (asset.type === 'VIDEO' ? openPlayable(asset) : openOriginal(asset.id))}
          accessibilityLabel={asset.type === 'VIDEO' ? 'Play video' : 'Open photo'}>
          <Image source={{ uri: url.data.url }} style={styles.image} accessibilityLabel={asset.type === 'VIDEO' ? 'Video' : 'Photo'} />
          {asset.type === 'VIDEO' ? (
            <View style={styles.playBadge}>
              <Text style={{ color: '#fff', fontWeight: '700' }}>▶</Text>
            </View>
          ) : null}
        </Pressable>
      ) : (
        <Pressable
          style={styles.placeholder}
          disabled={!usable}
          onPress={() => (asset.type === 'PHOTO' ? openOriginal(asset.id) : openPlayable(asset))}>
          <Text style={{ color: rejected ? t.danger : t.textMuted, fontWeight: '600', textAlign: 'center' }}>
            {usable ? (asset.type === 'VIDEO' ? '▶ Video' : '♪ Audio') : (STATUS_LABEL[asset.uploadStatus] ?? asset.uploadStatus)}
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

/** Video and audio open the PLAYBACK rendition when there is one (it plays everywhere), else the original. */
async function openPlayable(asset: Asset) {
  const { url } = await api.downloadUrl(asset.id, asset.variants.includes('PLAYBACK') ? 'PLAYBACK' : undefined);
  await Linking.openURL(url);
}

/** Tapping a thumbnail opens the full-size original, fetched only when asked for. */
async function openOriginal(assetId: string) {
  const { url } = await api.downloadUrl(assetId);
  await Linking.openURL(url);
}

export function AssetGallery({
  assets,
  onRemove,
  variant = 'THUMB',
}: {
  assets: Asset[];
  onRemove?: (asset: Asset) => void;
  /** Which derivative to show: THUMB for grids, PREVIEW for larger views such as Relive. */
  variant?: AssetVariant;
}) {
  const t = useTheme();
  if (assets.length === 0) return null;
  const scanning = assets.some((a) => a.uploadStatus === 'PENDING_SCAN');
  const rejected = assets.filter((a) => a.uploadStatus === 'REJECTED').length;
  return (
    <View style={{ gap: space.sm }}>
      <View style={styles.grid}>
        {assets.map((a) => (
          <AssetTile key={a.id} asset={a} variant={variant} onRemove={onRemove ? () => onRemove(a) : undefined} />
        ))}
      </View>
      <Body muted style={{ fontSize: 12 }}>
        {assets.length} item{assets.length === 1 ? '' : 's'}
        {scanning ? ' · running a safety check before you can lock' : ''}
      </Body>
      {rejected > 0 ? (
        <Body style={{ fontSize: 13, color: t.danger }}>
          {rejected === 1 ? 'One file was' : `${rejected} files were`} blocked by the safety check. Remove{' '}
          {rejected === 1 ? 'it' : 'them'} to lock this memory.
        </Body>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tile: { width: 104, height: 104, borderRadius: radius.md, borderWidth: 1, overflow: 'hidden' },
  image: { width: '100%', height: '100%' },
  placeholder: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xs },
  playBadge: {
    position: 'absolute',
    bottom: 6,
    left: 6,
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
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
