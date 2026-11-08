import { Children, Fragment, useState, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type TextInputProps,
  type TextProps,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { MemoryState } from '@/api/types';
import { radius, space, useTheme } from '@/theme';

/* ─── Layout ─────────────────────────────────────────────────────────────── */

export function Screen({ children, scroll = true }: { children: ReactNode; scroll?: boolean }) {
  const t = useTheme();
  const body = <View style={styles.screenBody}>{children}</View>;
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.background }} edges={['bottom', 'left', 'right']}>
      {scroll ? (
        <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ paddingBottom: space.xxxl }}>
          {body}
        </ScrollView>
      ) : (
        body
      )}
    </SafeAreaView>
  );
}

/** A titled group of content with consistent rhythm. */
export function Section({ title, action, children }: { title?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <View style={{ gap: space.md }}>
      {title || action ? (
        <View style={styles.sectionHeader}>
          {title ? <Label>{title}</Label> : <View />}
          {action}
        </View>
      ) : null}
      {children}
    </View>
  );
}

export function Divider() {
  const t = useTheme();
  return <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: t.border }} />;
}

/* ─── Type ───────────────────────────────────────────────────────────────── */

export function Title({ children, subtitle }: { children: ReactNode; subtitle?: ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ gap: space.xs }}>
      <Text accessibilityRole="header" style={[styles.title, { color: t.text }]}>
        {children}
      </Text>
      {subtitle ? <Text style={[styles.body, { color: t.textMuted }]}>{subtitle}</Text> : null}
    </View>
  );
}

export function Body({ style, muted, ...props }: TextProps & { muted?: boolean }) {
  const t = useTheme();
  return <Text {...props} style={[styles.body, { color: muted ? t.textMuted : t.text }, style]} />;
}

export function Label({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Text style={[styles.label, { color: t.textMuted }]}>{children}</Text>;
}

/** Text link for headers and inline actions. */
export function TextLink({ title, onPress, muted }: { title: string; onPress: () => void; muted?: boolean }) {
  const t = useTheme();
  return (
    <Pressable accessibilityRole="link" onPress={onPress} hitSlop={12} style={({ pressed }) => pressed && { opacity: 0.5 }}>
      <Text style={{ color: muted ? t.textMuted : t.accent, fontSize: 15, fontWeight: '500' }}>{title}</Text>
    </Pressable>
  );
}

/* ─── Inputs ─────────────────────────────────────────────────────────────── */

export function Field({ label, style, onFocus, onBlur, ...props }: TextInputProps & { label: string }) {
  const t = useTheme();
  const [focused, setFocused] = useState(false);
  return (
    <View style={{ gap: space.sm }}>
      <Text style={[styles.fieldLabel, { color: focused ? t.text : t.textMuted }]}>{label}</Text>
      <TextInput
        placeholderTextColor={t.textFaint}
        selectionColor={t.accent}
        {...props}
        onFocus={(e) => {
          setFocused(true);
          onFocus?.(e);
        }}
        onBlur={(e) => {
          setFocused(false);
          onBlur?.(e);
        }}
        style={[
          styles.input,
          {
            color: t.text,
            backgroundColor: focused ? t.surface : t.surfaceMuted,
            borderColor: focused ? t.accent : 'transparent',
          },
          props.multiline && { minHeight: 132, textAlignVertical: 'top' },
          style,
        ]}
      />
    </View>
  );
}

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  title,
  onPress,
  variant = 'primary',
  loading,
  disabled,
}: {
  title: string;
  onPress: () => void;
  variant?: ButtonVariant;
  loading?: boolean;
  disabled?: boolean;
}) {
  const t = useTheme();
  const palette: Record<ButtonVariant, { bg: string; fg: string; border: string }> = {
    primary: { bg: t.ink, fg: t.inkText, border: t.ink },
    secondary: { bg: 'transparent', fg: t.text, border: t.border },
    ghost: { bg: 'transparent', fg: t.textMuted, border: 'transparent' },
    danger: { bg: t.dangerSoft, fg: t.danger, border: t.dangerSoft },
  };
  const { bg, fg, border } = palette[variant];
  const inactive = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy: loading }}
      onPress={onPress}
      disabled={inactive}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, borderColor: border },
        variant === 'ghost' && { minHeight: 40 },
        disabled && !loading && { opacity: 0.35 },
        pressed && { opacity: 0.75, transform: [{ scale: 0.985 }] },
      ]}>
      {loading ? <ActivityIndicator color={fg} /> : <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

/** Selectable pill, e.g. timeline filters. */
export function Chip({ label, active, onPress }: { label: string; active?: boolean; onPress: () => void }) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      style={({ pressed }) => [
        styles.chip,
        { backgroundColor: active ? t.ink : t.surfaceMuted },
        pressed && { opacity: 0.7 },
      ]}>
      <Text style={{ color: active ? t.inkText : t.textMuted, fontSize: 14, fontWeight: '500' }}>{label}</Text>
    </Pressable>
  );
}

/* ─── Surfaces ───────────────────────────────────────────────────────────── */

export function Card({ children, onPress, style }: { children: ReactNode; onPress?: () => void; style?: ViewStyle }) {
  const t = useTheme();
  const cardStyle = [styles.card, { backgroundColor: t.surface, borderColor: t.border }, style];
  if (!onPress) return <View style={cardStyle}>{children}</View>;
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [cardStyle, pressed && { backgroundColor: t.surfaceMuted, transform: [{ scale: 0.995 }] }]}>
      {children}
    </Pressable>
  );
}

/** A card of list rows separated by hairlines (settings-style). */
export function Group({ children }: { children: ReactNode }) {
  const t = useTheme();
  const items = Children.toArray(children).filter(Boolean);
  return (
    <View style={[styles.group, { backgroundColor: t.surface, borderColor: t.border }]}>
      {items.map((child, i) => (
        <Fragment key={i}>
          {i > 0 ? <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: t.border, marginLeft: space.lg }} /> : null}
          {child}
        </Fragment>
      ))}
    </View>
  );
}

export function Row({
  title,
  subtitle,
  onPress,
  leading,
  trailing,
  tone,
}: {
  title: string;
  subtitle?: string;
  onPress?: () => void;
  leading?: ReactNode;
  trailing?: ReactNode;
  tone?: 'danger';
}) {
  const t = useTheme();
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      style={({ pressed }) => [styles.row, pressed && { backgroundColor: t.surfaceMuted }]}>
      {leading}
      <View style={{ flex: 1, gap: 2 }}>
        <Text style={{ color: tone === 'danger' ? t.danger : t.text, fontSize: 16, fontWeight: '500' }}>{title}</Text>
        {subtitle ? <Text style={{ color: t.textMuted, fontSize: 14 }}>{subtitle}</Text> : null}
      </View>
      {trailing ?? (onPress && tone !== 'danger' ? <Text style={{ color: t.textFaint, fontSize: 22 }}>›</Text> : null)}
    </Pressable>
  );
}

/** Rounded initial, used for vaults and people. */
export function Monogram({ text, size = 40, tint }: { text: string; size?: number; tint?: boolean }) {
  const t = useTheme();
  const initial = text.trim().charAt(0).toUpperCase() || '·';
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 3,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: tint ? t.accentSoft : t.surfaceMuted,
      }}>
      <Text style={{ color: tint ? t.accent : t.text, fontSize: size * 0.42, fontWeight: '600' }}>{initial}</Text>
    </View>
  );
}

export function ProgressBar({ value, max, tone }: { value: number; max: number; tone?: 'danger' }) {
  const t = useTheme();
  const pct = Math.min(100, (value / Math.max(1, max)) * 100);
  return (
    <View
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max, now: value }}
      style={{ height: 6, borderRadius: radius.pill, backgroundColor: t.surfaceMuted, overflow: 'hidden' }}>
      <View
        style={{ height: '100%', width: `${pct}%`, borderRadius: radius.pill, backgroundColor: tone === 'danger' ? t.danger : t.accent }}
      />
    </View>
  );
}

/** The brand mark: a simple padlock drawn with views, no image assets. */
export function LockMark({ size = 56 }: { size?: number }) {
  const t = useTheme();
  const body = size * 0.56;
  const shackle = size * 0.36;
  return (
    <View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={{
        width: size,
        height: size,
        borderRadius: size * 0.3,
        backgroundColor: t.ink,
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <View
        style={{
          width: shackle,
          height: shackle * 0.75,
          borderWidth: size * 0.065,
          borderBottomWidth: 0,
          borderColor: t.inkText,
          borderTopLeftRadius: shackle,
          borderTopRightRadius: shackle,
          marginBottom: -1,
        }}
      />
      <View
        style={{
          width: body,
          height: body * 0.68,
          borderRadius: size * 0.08,
          backgroundColor: t.accent,
          alignItems: 'center',
          justifyContent: 'center',
        }}>
        <View style={{ width: size * 0.07, height: size * 0.14, borderRadius: size, backgroundColor: t.ink }} />
      </View>
    </View>
  );
}

/* ─── Status ─────────────────────────────────────────────────────────────── */

const STATE_LABEL: Record<MemoryState, string> = {
  DRAFT: 'Draft',
  SEALING: 'Sealing',
  LOCKED: 'Locked',
  ARCHIVED: 'Archived',
};

export function StateBadge({ state, capsule }: { state: MemoryState; capsule?: boolean }) {
  const t = useTheme();
  const color = state === 'DRAFT' ? t.draft : state === 'SEALING' ? t.sealing : t.locked;
  return (
    <View style={styles.badge}>
      <View
        style={{
          width: 7,
          height: 7,
          borderRadius: 4,
          backgroundColor: state === 'DRAFT' ? 'transparent' : color,
          borderWidth: 1.5,
          borderColor: color,
        }}
      />
      <Text style={[styles.badgeText, { color }]}>{capsule ? 'Time capsule' : STATE_LABEL[state]}</Text>
    </View>
  );
}

export function ErrorText({ error }: { error: unknown }) {
  const t = useTheme();
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <View style={[styles.error, { backgroundColor: t.dangerSoft }]}>
      <Text style={{ color: t.danger, fontSize: 14, lineHeight: 20 }}>{message}</Text>
    </View>
  );
}

export function Loading() {
  const t = useTheme();
  return (
    <View style={styles.center}>
      <ActivityIndicator color={t.textMuted} />
    </View>
  );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
  const t = useTheme();
  return (
    <View style={[styles.center, { gap: space.sm, paddingVertical: space.xxxl }]}>
      <View style={[styles.emptyDot, { borderColor: t.border }]} />
      <Body style={{ fontWeight: '600' }}>{title}</Body>
      {hint ? (
        <Body muted style={{ textAlign: 'center', maxWidth: 280, fontSize: 15 }}>
          {hint}
        </Body>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screenBody: {
    paddingHorizontal: space.xl,
    paddingTop: space.lg,
    gap: space.xl,
    maxWidth: 640,
    width: '100%',
    alignSelf: 'center',
  },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: 32, fontWeight: '700', letterSpacing: -0.8, lineHeight: 38 },
  body: { fontSize: 16, lineHeight: 24 },
  label: { fontSize: 12, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 1.2 },
  fieldLabel: { fontSize: 14, fontWeight: '500' },
  input: {
    borderWidth: 1.5,
    borderRadius: radius.md,
    paddingHorizontal: space.lg,
    paddingVertical: 14,
    fontSize: 16,
  },
  button: {
    minHeight: 52,
    borderRadius: radius.pill,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.xl,
  },
  buttonText: { fontSize: 16, fontWeight: '600', letterSpacing: 0.1 },
  chip: { paddingHorizontal: 14, paddingVertical: 7, borderRadius: radius.pill },
  card: { borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.lg, padding: space.lg + 2, gap: space.sm },
  group: { borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.lg, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg, paddingVertical: 14, minHeight: 52 },
  badge: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  badgeText: { fontSize: 12, fontWeight: '600', letterSpacing: 0.3 },
  error: { borderRadius: radius.md, paddingHorizontal: space.md, paddingVertical: space.sm + 2 },
  center: { alignItems: 'center', justifyContent: 'center', padding: space.xxl },
  emptyDot: { width: 44, height: 44, borderRadius: 22, borderWidth: 1.5, borderStyle: 'dashed', marginBottom: space.sm },
});
