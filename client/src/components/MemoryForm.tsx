import { useState } from 'react';
import { Switch, View } from 'react-native';

import type { MemoryInput } from '@/api/types';
import { space, useTheme } from '@/theme';

import { Body, Button, ErrorText, Field } from './ui';

function toDateInput(iso: string | null | undefined): string {
  return iso ? iso.slice(0, 10) : '';
}

/** Parses YYYY-MM-DD as local midnight; returns undefined for empty input, null for invalid input. */
function parseDate(value: string): string | undefined | null {
  if (!value.trim()) return undefined;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value.trim())) return null;
  const d = new Date(`${value.trim()}T00:00:00`);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export function MemoryForm({
  initial,
  submitLabel,
  onSubmit,
  busy,
  error,
}: {
  initial?: Partial<MemoryInput>;
  submitLabel: string;
  onSubmit: (input: MemoryInput) => void;
  busy?: boolean;
  error?: unknown;
}) {
  const t = useTheme();
  const [title, setTitle] = useState(initial?.title ?? '');
  const [thought, setThought] = useState(initial?.thought ?? '');
  const [feeling, setFeeling] = useState(initial?.feeling ?? '');
  const [location, setLocation] = useState(initial?.location ?? '');
  const [occurredOn, setOccurredOn] = useState(toDateInput(initial?.occurredAt));
  const [capsule, setCapsule] = useState(!!initial?.revealAt);
  const [revealOn, setRevealOn] = useState(toDateInput(initial?.revealAt));
  const [formError, setFormError] = useState<string | null>(null);

  const submit = () => {
    const occurredAt = parseDate(occurredOn);
    const revealAt = capsule ? parseDate(revealOn) : undefined;
    if (occurredAt === null || revealAt === null) {
      setFormError('Dates must look like 2026-06-15.');
      return;
    }
    if (capsule && (!revealAt || new Date(revealAt) <= new Date())) {
      setFormError('A time capsule needs an opening date in the future.');
      return;
    }
    setFormError(null);
    onSubmit({
      title: title.trim(),
      thought: thought.trim() || null,
      feeling: feeling.trim() || null,
      location: location.trim() || null,
      occurredAt: occurredAt ?? null,
      revealAt: revealAt ?? null,
    });
  };

  return (
    <View style={{ gap: space.lg }}>
      <Field label="Title" placeholder="The day everything changed" value={title} onChangeText={setTitle} maxLength={200} />
      <Field
        label="What were you thinking?"
        placeholder="Write it the way you'd want to read it in ten years."
        value={thought}
        onChangeText={setThought}
        multiline
        maxLength={20000}
      />
      <Field label="How did it feel?" placeholder="nervous, grateful, free…" value={feeling} onChangeText={setFeeling} maxLength={200} />
      <Field label="Where (optional)" value={location} onChangeText={setLocation} maxLength={300} />
      <Field
        label="When (YYYY-MM-DD, defaults to today)"
        placeholder="2026-06-15"
        value={occurredOn}
        onChangeText={setOccurredOn}
        autoCapitalize="none"
      />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md }}>
        <View style={{ flex: 1 }}>
          <Body style={{ fontWeight: '600' }}>Time capsule</Body>
          <Body muted>Keep this sealed, even from you, until a date you choose.</Body>
        </View>
        <Switch value={capsule} onValueChange={setCapsule} trackColor={{ true: t.accent }} />
      </View>
      {capsule ? (
        <Field label="Opens on (YYYY-MM-DD)" placeholder="2036-06-15" value={revealOn} onChangeText={setRevealOn} autoCapitalize="none" />
      ) : null}
      <ErrorText error={formError ?? error} />
      <Button title={submitLabel} onPress={submit} loading={busy} disabled={!title.trim()} />
    </View>
  );
}
