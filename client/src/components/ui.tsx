import type { ReactNode } from 'react';
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

export function Screen({ children, scroll = true }: { children: ReactNode; scroll?: boolean }) {
  const t = useTheme();
  const body = <View style={styles.screenBody}>{children}</View>;
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.background }} edges={['bottom', 'left', 'right']}>
      {scroll ? <ScrollView keyboardShouldPersistTaps="handled">{body}</ScrollView> : body}
    </SafeAreaView>
  );
}

export function Title({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Text style={[styles.title, { color: t.text }]}>{children}</Text>;
}

export function Body({ style, muted, ...props }: TextProps & { muted?: boolean }) {
  const t = useTheme();
  return <Text {...props} style={[styles.body, { color: muted ? t.textMuted : t.text }, style]} />;
}

export function Label({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Text style={[styles.label, { color: t.textMuted }]}>{children}</Text>;
}

export function Field({ label, style, ...props }: TextInputProps & { label: string }) {
  const t = useTheme();
  return (
    <View style={{ gap: space.xs }}>
      <Label>{label}</Label>
      <TextInput
        placeholderTextColor={t.textMuted}
        {...props}
        style={[
          styles.input,
          { color: t.text, backgroundColor: t.surface, borderColor: t.border },
          props.multiline && { minHeight: 120, textAlignVertical: 'top' },
          style,
        ]}
      />
    </View>
  );
}

type ButtonVariant = 'primary' | 'secondary' | 'danger';

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
  const bg = variant === 'primary' ? t.accent : variant === 'danger' ? t.danger : 'transparent';
  const fg = variant === 'secondary' ? t.text : t.accentText;
  const inactive = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy: loading }}
      onPress={onPress}
      disabled={inactive}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, borderColor: variant === 'secondary' ? t.border : bg },
        (pressed || inactive) && { opacity: 0.6 },
      ]}>
      {loading ? <ActivityIndicator color={fg} /> : <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>}
    </Pressable>
  );
}

export function Card({ children, onPress, style }: { children: ReactNode; onPress?: () => void; style?: ViewStyle }) {
  const t = useTheme();
  const cardStyle = [styles.card, { backgroundColor: t.surface, borderColor: t.border }, style];
  if (!onPress) return <View style={cardStyle}>{children}</View>;
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [cardStyle, pressed && { opacity: 0.8 }]}>
      {children}
    </Pressable>
  );
}

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
    <View style={[styles.badge, { borderColor: color }]}>
      <Text style={[styles.badgeText, { color }]}>
        {state === 'DRAFT' ? '○' : state === 'SEALING' ? '◐' : '●'} {capsule ? 'Time capsule' : STATE_LABEL[state]}
      </Text>
    </View>
  );
}

export function ErrorText({ error }: { error: unknown }) {
  const t = useTheme();
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  return <Text style={[styles.body, { color: t.danger }]}>{message}</Text>;
}

export function Loading() {
  const t = useTheme();
  return (
    <View style={styles.center}>
      <ActivityIndicator color={t.accent} />
    </View>
  );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
  return (
    <View style={[styles.center, { gap: space.sm }]}>
      <Body style={{ fontWeight: '600' }}>{title}</Body>
      {hint ? <Body muted style={{ textAlign: 'center' }}>{hint}</Body> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  screenBody: { padding: space.lg, gap: space.lg, maxWidth: 720, width: '100%', alignSelf: 'center' },
  title: { fontSize: 28, fontWeight: '700', letterSpacing: -0.5 },
  body: { fontSize: 16, lineHeight: 23 },
  label: { fontSize: 13, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.6 },
  input: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: space.md, paddingVertical: space.md, fontSize: 16 },
  button: {
    minHeight: 48,
    borderRadius: radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: space.lg,
  },
  buttonText: { fontSize: 16, fontWeight: '600' },
  card: { borderWidth: 1, borderRadius: radius.lg, padding: space.lg, gap: space.sm },
  badge: { alignSelf: 'flex-start', borderWidth: 1, borderRadius: 999, paddingHorizontal: space.sm, paddingVertical: 2 },
  badgeText: { fontSize: 12, fontWeight: '600' },
  center: { alignItems: 'center', justifyContent: 'center', padding: space.xxl },
});
