import { useState } from 'react';
import { Platform, Switch, Text, View } from 'react-native';

import type { Coordinates, MemoryInput } from '@/api/types';
import { currentPosition, formatPin, geocodingSupported, lookUpPlace } from '@/lib/geo';

import { mapSupported } from './MemoryMap';
import { PinPicker } from './PinPicker';
import { space, useTheme } from '@/theme';

import { Body, Button, ErrorText, Field, TextLink } from './ui';

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
  const [coordinates, setCoordinates] = useState<Coordinates | null>(initial?.coordinates ?? null);
  const [locating, setLocating] = useState(false);
  const [pinError, setPinError] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);

  const pin = async (find: () => Promise<Coordinates | null>, notFound: string) => {
    setLocating(true);
    setPinError(null);
    try {
      const found = await find();
      if (found) setCoordinates(found);
      else setPinError(notFound);
    } catch (e) {
      setPinError(e instanceof Error ? e.message : String(e));
    } finally {
      setLocating(false);
    }
  };
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
      coordinates,
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
      <View style={{ gap: space.sm }}>
        <Text style={{ color: t.textMuted, fontSize: 14, fontWeight: '500' }}>On the map (optional)</Text>
        <Body muted={!coordinates} style={{ fontSize: 15 }}>
          {coordinates ? `📍 ${formatPin(coordinates)}` : 'Not pinned yet. Pinned memories appear on the vault’s map.'}
        </Body>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.lg, alignItems: 'center' }}>
          <TextLink
            title={locating ? 'Locating…' : 'Use my location'}
            onPress={() => void pin(currentPosition, 'Couldn’t get your position.')}
          />
          {geocodingSupported && location.trim() ? (
            <TextLink
              title="Find “Where” on the map"
              onPress={() => void pin(() => lookUpPlace(location), `Couldn’t find “${location.trim()}”. Try a fuller address.`)}
            />
          ) : null}
          {mapSupported ? <TextLink title="Pick on the map" onPress={() => setPicking(true)} /> : null}
          {coordinates ? <TextLink title="Remove pin" muted onPress={() => setCoordinates(null)} /> : null}
        </View>
        {pinError ? <Body style={{ fontSize: 13, color: t.danger }}>{pinError}</Body> : null}
        <PinPicker
          visible={picking}
          initial={coordinates}
          onPick={(c) => {
            setCoordinates(c);
            setPinError(null);
            setPicking(false);
          }}
          onClose={() => setPicking(false)}
        />
        {Platform.OS !== 'web' ? (
          <Body muted style={{ fontSize: 12 }}>
            The position is stored encrypted with the memory and can’t change once it’s locked.
          </Body>
        ) : null}
      </View>
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
