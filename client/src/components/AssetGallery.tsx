import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Image, Linking, Platform, Pressable, StyleSheet, Text, View } from 'react-native';

import * as Sharing from 'expo-sharing';

import { api } from '@/api/lock';
import { evictDecrypted, openSealedAsset } from '@/lib/sealedFiles';
import type { Asset, AssetVariant, UploadStatus } from '@/api/types';
import { radius, space, useTheme } from '@/theme';

import { MediaPlayer } from './MediaPlayer';
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
/** Where a sealed asset's key lives; set only when the media is end-to-end encrypted (3.6d). */
export interface SealedMediaContext {
  vaultId: string;
  memoryId: string;
  keyVersion: number;
}

/**
 * Sealed media: downloaded and decrypted on the device into the cache. Video and audio play in the
 * app (the decrypted copy is deleted when the player closes); photos open through the share sheet.
 */
function SealedTile({ asset, ctx, onRemove }: { asset: Asset; ctx: SealedMediaContext; onRemove?: () => void }) {
  const t = useTheme();
  const usable = asset.uploadStatus === 'VERIFIED' || asset.uploadStatus === 'ATTACHED';
  const hasThumb = asset.variants.includes('THUMB');
  const thumb = useQuery({
    queryKey: ['sealed-thumb', asset.id],
    queryFn: () => openSealedAsset(ctx.vaultId, ctx.keyVersion, ctx.memoryId, asset, true),
    enabled: usable && hasThumb,
    staleTime: Infinity,
  });
  const [playing, setPlaying] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const playable = asset.type !== 'PHOTO';
  const open = async () => {
    setBusy(true);
    try {
      const uri = await openSealedAsset(ctx.vaultId, ctx.keyVersion, ctx.memoryId, asset, false);
      if (playable) setPlaying(uri);
      else await Sharing.shareAsync(uri, { mimeType: asset.mimeType });
    } finally {
      setBusy(false);
    }
  };
  const close = () => {
    setPlaying(null);
    evictDecrypted(asset.id); // plaintext only lives while it is being played
  };
  return (
    <View style={[styles.tile, { backgroundColor: t.surfaceMuted, borderColor: t.border }]}>
      <MediaPlayer uri={playing} type={asset.type} onClose={close} />
      <Pressable
        style={styles.image}
        disabled={!usable || busy}
        onPress={() => void open()}
        accessibilityLabel={playable ? `Play ${asset.type === 'VIDEO' ? 'video' : 'audio'}` : 'Open photo'}>
        {thumb.data ? (
          <Image source={{ uri: thumb.data }} style={styles.image} accessibilityLabel="Photo" />
        ) : (
          <View style={styles.placeholder}>
            <Text style={{ color: t.textMuted, fontWeight: '600', textAlign: 'center' }}>
              {busy ? 'Decrypting…' : usable ? (asset.type === 'VIDEO' ? '▶ Video' : asset.type === 'AUDIO' ? '♪ Audio' : 'Photo') : (STATUS_LABEL[asset.uploadStatus] ?? asset.uploadStatus)}
            </Text>
          </View>
        )}
      </Pressable>
      {onRemove ? (
        <Pressable onPress={onRemove} style={[styles.remove, { backgroundColor: t.background }]} accessibilityLabel="Remove">
          <Text style={{ color: t.text, fontWeight: '700' }}>✕</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

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
  const [playing, setPlaying] = useState<string | null>(null);
  const openPlayable = async () => {
    const uri = await playableUrl(asset);
    if (Platform.OS === 'web') await Linking.openURL(uri);
    else setPlaying(uri);
  };

  return (
    <View
      style={[styles.tile, { backgroundColor: t.surfaceMuted, borderColor: rejected ? t.danger : t.border }]}
      accessibilityLabel={rejected ? 'Blocked by the safety check' : undefined}>
      <MediaPlayer uri={playing} type={asset.type} onClose={() => setPlaying(null)} />
      {wanted && url.data ? (
        <Pressable
          style={styles.image}
          onPress={() => void (asset.type === 'VIDEO' ? openPlayable() : openOriginal(asset.id))}
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
          onPress={() => void (asset.type === 'PHOTO' ? openOriginal(asset.id) : openPlayable())}>
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

/** Video and audio play the PLAYBACK rendition when there is one (it plays everywhere), else the original. */
async function playableUrl(asset: Asset): Promise<string> {
  const { url } = await api.downloadUrl(asset.id, asset.variants.includes('PLAYBACK') ? 'PLAYBACK' : undefined);
  return url;
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
  sealed,
}: {
  assets: Asset[];
  onRemove?: (asset: Asset) => void;
  /** Set for a sealed vault: tiles decrypt on the device instead of loading a URL. */
  sealed?: SealedMediaContext;
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
        {assets.map((a) =>
          sealed ? (
            <SealedTile key={a.id} asset={a} ctx={sealed} onRemove={onRemove ? () => onRemove(a) : undefined} />
          ) : (
            <AssetTile key={a.id} asset={a} variant={variant} onRemove={onRemove ? () => onRemove(a) : undefined} />
          ),
        )}
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
