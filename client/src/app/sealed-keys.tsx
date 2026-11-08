import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Device from 'expo-device';
import * as Clipboard from 'expo-clipboard';
import { router } from 'expo-router';
import { useState } from 'react';
import { Text, View } from 'react-native';

import { api } from '@/api/lock';
import { MfaCodeField } from '@/components/MfaCodeField';
import { QrCode } from '@/components/QrCode';
import { collect, startRequest } from '@/lib/deviceApproval';
import { Body, Button, Card, ErrorText, Field, Label, Loading, Screen } from '@/components/ui';
import { fingerprint, fromBase64, generateRecoveryPhrase, isValidRecoveryPhrase } from '@/lib/sealed';
import {
  adoptApprovedKeys,
  changePassphrase,
  deviceUnlocked,
  recoverWithPhrase,
  setUpKeys,
  unlockWithPassphraseOnDevice,
} from '@/lib/sealedVault';
import { useKeyBundle, useKeysVersion } from '@/lib/useSealed';
import { passphraseStrength } from '@/lib/passphraseStrength';
import { mono, space, useTheme } from '@/theme';

const MIN_PASSPHRASE = 12;

/**
 * Copies the recovery phrase and clears the clipboard a minute later if it still holds it, so the
 * words don't sit in clipboard history or reach other apps indefinitely (security review F8).
 */
async function copyPhraseBriefly(phrase: string) {
  await Clipboard.setStringAsync(phrase);
  setTimeout(() => {
    void Clipboard.getStringAsync().then((now) => (now === phrase ? Clipboard.setStringAsync('') : undefined));
  }, 60_000);
}

/** The account's name and email, which a passphrase must not lean on. */
function personal(me?: { displayName: string; email: string }): string[] {
  if (!me) return [];
  return [me.displayName, ...me.displayName.split(/\s+/), me.email, me.email.split('@')[0]];
}

function strongEnough(passphrase: string, me?: { displayName: string; email: string }): boolean {
  return passphrase.length >= MIN_PASSPHRASE && passphraseStrength(passphrase, personal(me)).acceptable;
}

/** Live strength meter under a new passphrase (security review F9). */
function StrengthMeter({ passphrase }: { passphrase: string }) {
  const t = useTheme();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  if (!passphrase) return null;
  const s = passphraseStrength(passphrase, personal(me.data));
  const color = s.level === 'strong' ? t.locked : s.level === 'fair' ? t.sealing : t.danger;
  const label = s.level === 'strong' ? 'Strong' : s.level === 'fair' ? 'Good enough' : 'Too easy to guess';
  return (
    <View style={{ gap: space.xs }}>
      <View
        accessibilityRole="progressbar"
        accessibilityLabel={`Passphrase strength: ${label}`}
        accessibilityValue={{ min: 0, max: 100, now: Math.min(100, s.bits) }}
        style={{ height: 4, borderRadius: 2, backgroundColor: t.surfaceMuted, overflow: 'hidden' }}>
        <View style={{ height: '100%', width: `${Math.min(100, (s.bits / 90) * 100)}%`, backgroundColor: color }} />
      </View>
      <Body style={{ fontSize: 13, color }}>
        {label}
        {s.hint ? <Body muted style={{ fontSize: 13 }}>{` · ${s.hint}`}</Body> : null}
      </Body>
    </View>
  );
}

/** Three word positions (one from each third of the phrase) to confirm it was written down. */
function pickCheckPositions(): number[] {
  return [2, 10, 18].map((start) => start + Math.floor(Math.random() * 4));
}

/**
 * Sealed Vault keys (roadmap 3.6): set up once (passphrase + 24-word recovery phrase), then unlock on
 * each device. Lock can't reset either secret: that is the point of end-to-end encryption.
 */
export default function SealedKeys() {
  const bundle = useKeyBundle();
  useKeysVersion();

  if (bundle.isPending) return <Loading />;
  if (bundle.error) {
    return (
      <Screen>
        <ErrorText error={bundle.error} />
      </Screen>
    );
  }
  if (!bundle.data) return <SetUp />;
  return (
    <Screen>
      <Card>
        <Body style={{ fontWeight: '600' }}>{deviceUnlocked() ? 'Unlocked on this device' : 'Locked on this device'}</Body>
        <Body muted>Key fingerprint</Body>
        {/* Computed here from the public key, not taken from the server's text (security review F1). */}
        <Body selectable style={{ fontFamily: mono, fontSize: 13 }}>{fingerprint(fromBase64(bundle.data.publicKey))}</Body>
        <Body muted style={{ fontSize: 13 }}>
          Compare it in person with what other members see next to your name under a sealed vault’s Members, to be
          sure nobody swapped a key.
        </Body>
      </Card>
      {deviceUnlocked() ? (
        <>
          <Button title="Approve a new device" onPress={() => router.push('/approve-device')} />
          <ChangePassphrase />
        </>
      ) : (
        <Unlock />
      )}
    </Screen>
  );
}

function SetUp() {
  const t = useTheme();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const queryClient = useQueryClient();
  // Generated once per visit (lazy state initialisers run a single time).
  const [phrase] = useState(generateRecoveryPhrase);
  const [step, setStep] = useState<'phrase' | 'confirm'>('phrase');
  const [check, setCheck] = useState('');
  const [passphrase, setPassphrase] = useState('');
  const [repeat, setRepeat] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  // Ask for three words by position, so the phrase was really written down.
  const [positions] = useState(pickCheckPositions);
  const words = phrase.split(' ');
  const checkOk = check.trim().toLowerCase().split(/\s+/).join(' ') === positions.map((i) => words[i]).join(' ');

  const create = useMutation({
    mutationFn: () => setUpKeys(password, passphrase, phrase, mfaCode),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['keyBundle'] });
      router.back();
    },
  });

  if (step === 'phrase') {
    return (
      <Screen>
        <Body>
          Sealed vaults are end-to-end encrypted: only members’ devices hold the key. Not even Lock can read them, so Lock
          also can’t help you back in if you lose access.
        </Body>
        <Label>Your recovery phrase</Label>
        <Card>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {words.map((w, i) => (
              <Text key={i} style={{ color: t.text, fontFamily: mono, width: '30%' }}>
                {i + 1}. {w}
              </Text>
            ))}
          </View>
        </Card>
        <Body style={{ color: t.danger }}>
          Write these 24 words on paper and keep them somewhere safe. They are the only way back in if you forget your
          passphrase on every device. Lock will never show them again.
        </Body>
        <Button title="Copy (clears in 1 minute)" variant="secondary" onPress={() => void copyPhraseBriefly(phrase)} />
        <Button title="I wrote it down" onPress={() => setStep('confirm')} />
      </Screen>
    );
  }

  return (
    <Screen>
      <Field
        label={`Words ${positions.map((i) => i + 1).join(', ')} of your phrase, separated by spaces`}
        value={check}
        onChangeText={setCheck}
        autoCapitalize="none"
        autoCorrect={false}
      />
      <Field
        label={`Sealed Vault passphrase (at least ${MIN_PASSPHRASE} characters)`}
        value={passphrase}
        onChangeText={setPassphrase}
        secureTextEntry
      />
      <StrengthMeter passphrase={passphrase} />
      <Body muted style={{ fontSize: 13 }}>
        Different from your account password. You’ll enter it once on each new device.
      </Body>
      <Field label="Repeat passphrase" value={repeat} onChangeText={setRepeat} secureTextEntry />
      <Field label="Account password" value={password} onChangeText={setPassword} secureTextEntry />
      <MfaCodeField value={mfaCode} onChangeText={setMfaCode} />
      {check && !checkOk ? <Body style={{ color: t.danger }}>Those words don’t match your phrase.</Body> : null}
      {repeat && repeat !== passphrase ? <Body style={{ color: t.danger }}>The passphrases don’t match.</Body> : null}
      <ErrorText error={create.error} />
      <Button
        title="Create my keys"
        onPress={() => create.mutate()}
        loading={create.isPending}
        disabled={!checkOk || !strongEnough(passphrase, me.data) || repeat !== passphrase || !password}
      />
      <Button title="Show the phrase again" variant="ghost" onPress={() => setStep('phrase')} />
    </Screen>
  );
}

function Unlock() {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const [mode, setMode] = useState<'passphrase' | 'recover' | 'device'>('passphrase');
  const [passphrase, setPassphrase] = useState('');
  const [phrase, setPhrase] = useState('');
  const [newPassphrase, setNewPassphrase] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const done = () => {
    void queryClient.invalidateQueries({ queryKey: ['vaultKeys'] });
    router.back();
  };
  const unlock = useMutation({ mutationFn: () => unlockWithPassphraseOnDevice(passphrase), onSuccess: done });
  const recover = useMutation({ mutationFn: () => recoverWithPhrase(password, phrase, newPassphrase, mfaCode), onSuccess: done });

  if (mode === 'passphrase') {
    return (
      <View style={{ gap: space.md }}>
        <Field label="Sealed Vault passphrase" value={passphrase} onChangeText={setPassphrase} secureTextEntry />
        <ErrorText error={unlock.error} />
        <Button title="Unlock" onPress={() => unlock.mutate()} loading={unlock.isPending} disabled={!passphrase} />
        <Body muted style={{ fontSize: 13 }}>Unlocking takes a few seconds: the passphrase is deliberately slow to guess.</Body>
        <Button title="Use another device instead" variant="ghost" onPress={() => setMode('device')} />
        <Button title="I forgot my passphrase" variant="ghost" onPress={() => setMode('recover')} />
      </View>
    );
  }
  if (mode === 'device') return <ApproveFromOtherDevice onDone={done} onBack={() => setMode('passphrase')} />;
  return (
    <View style={{ gap: space.md }}>
      <Field label="Recovery phrase (24 words)" value={phrase} onChangeText={setPhrase} multiline autoCapitalize="none" autoCorrect={false} />
      <Field
        label={`New passphrase (at least ${MIN_PASSPHRASE} characters)`}
        value={newPassphrase}
        onChangeText={setNewPassphrase}
        secureTextEntry
      />
      <StrengthMeter passphrase={newPassphrase} />
      <Field label="Account password" value={password} onChangeText={setPassword} secureTextEntry />
      <MfaCodeField value={mfaCode} onChangeText={setMfaCode} />
      <ErrorText error={recover.error} />
      <Button
        title="Recover"
        onPress={() => recover.mutate()}
        loading={recover.isPending}
        disabled={!isValidRecoveryPhrase(phrase) || !strongEnough(newPassphrase, me.data) || !password}
      />
      <Button title="Back" variant="ghost" onPress={() => setMode('passphrase')} />
    </View>
  );
}

/** The new-device side of 3.6e: show a code, wait for an unlocked device to scan it. */
function ApproveFromOtherDevice({ onDone, onBack }: { onDone: () => void; onBack: () => void }) {
  const bundle = useKeyBundle();
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const request = useQuery({
    queryKey: ['deviceRequest'],
    queryFn: () => startRequest(Device.deviceName ?? Device.modelName ?? undefined),
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  const waiting = useQuery({
    queryKey: ['deviceRequestCollect', request.data?.id],
    enabled: !!request.data && !!me.data && !!bundle.data,
    refetchInterval: (q) => (q.state.data ? false : 3000),
    queryFn: async () => {
      const pair = await collect(request.data!, me.data!.id, bundle.data!.publicKey);
      if (pair) {
        await adoptApprovedKeys(pair);
        onDone();
      }
      return pair ? true : null;
    },
  });
  return (
    <View style={{ gap: space.md }}>
      <Body>On a device where your Sealed Vault keys are unlocked, open Settings → Sealed Vault keys → “Approve a new device” and scan this code.</Body>
      {request.data ? (
        <>
          <QrCode value={request.data.qr} />
          <Body muted style={{ textAlign: 'center', fontFamily: mono, fontSize: 12 }}>{request.data.fingerprint}</Body>
          <Body muted style={{ textAlign: 'center' }}>Waiting for approval… The code expires in 10 minutes.</Body>
        </>
      ) : (
        <Loading />
      )}
      <ErrorText error={request.error ?? waiting.error} />
      <Button
        title="Cancel"
        variant="secondary"
        onPress={() => {
          if (request.data) void api.cancelDeviceApproval(request.data.id).catch(() => undefined);
          onBack();
        }}
      />
    </View>
  );
}

function ChangePassphrase() {
  const me = useQuery({ queryKey: ['me'], queryFn: api.me });
  const [next, setNext] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const change = useMutation({ mutationFn: () => changePassphrase(password, next, mfaCode) });
  return (
    <View style={{ gap: space.md }}>
      <Label>Change passphrase</Label>
      <Body muted>Your keys and recovery phrase stay the same.</Body>
      <Field label={`New passphrase (at least ${MIN_PASSPHRASE} characters)`} value={next} onChangeText={setNext} secureTextEntry />
      <StrengthMeter passphrase={next} />
      <Field label="Account password" value={password} onChangeText={setPassword} secureTextEntry />
      <MfaCodeField value={mfaCode} onChangeText={setMfaCode} />
      <ErrorText error={change.error} />
      {change.isSuccess ? <Body>Passphrase changed.</Body> : null}
      <Button
        title="Change passphrase"
        variant="secondary"
        onPress={() => change.mutate()}
        loading={change.isPending}
        disabled={!strongEnough(next, me.data) || !password}
      />
    </View>
  );
}
