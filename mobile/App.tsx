/**
 * Sayflux setup screen: walks through the one-time setup (microphone, keyboard), holds
 * the clean-up settings, and has a text box to try the keyboard in. The dictation itself
 * happens in the native keyboard (android/.../ime/SayfluxKeyboard.kt). Speech is turned
 * into text on the phone (the model ships with the app); only the text is sent to Gemini
 * for the optional smart clean-up.
 *
 * @format
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Linking,
  PermissionsAndroid,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import Sayflux, { type Settings, type Status } from './specs/NativeSayflux';

type Palette = typeof light;

const light = {
  bg: '#F6F6FA',
  card: '#FFFFFF',
  text: '#16161D',
  muted: '#62626F',
  border: '#E3E3EA',
  accent: '#5B5BD6',
  accentText: '#FFFFFF',
  done: '#2F9E6B',
  track: '#E6E6F0',
};

const dark: Palette = {
  bg: '#0F0F14',
  card: '#1A1A22',
  text: '#F1F1F5',
  muted: '#A0A0AE',
  border: '#2C2C38',
  accent: '#8B8BF5',
  accentText: '#0F0F14',
  done: '#4CC38A',
  track: '#2C2C38',
};

function useStatus() {
  const [status, setStatus] = useState<Status | null>(null);
  const refresh = useCallback(() => {
    Sayflux.getStatus()
      .then(setStatus)
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    // The keyboard picker is a dialog over the app, so closing it doesn't change
    // AppState. Poll while the app is in front so the checklist stays current.
    let timer: ReturnType<typeof setInterval> | null = setInterval(
      refresh,
      1500,
    );
    const sub = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refresh();
        timer ??= setInterval(refresh, 1500);
      } else if (timer) {
        clearInterval(timer);
        timer = null;
      }
    });
    return () => {
      sub.remove();
      if (timer) {
        clearInterval(timer);
      }
    };
  }, [refresh]);

  return [status, refresh] as const;
}

function App() {
  const isDark = useColorScheme() === 'dark';
  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
      <SetupScreen c={isDark ? dark : light} />
    </SafeAreaProvider>
  );
}

function SetupScreen({ c }: { c: Palette }) {
  const insets = useSafeAreaInsets();
  const [status, refresh] = useStatus();

  const requestMic = async () => {
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    );
    if (result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) {
      Linking.openSettings();
    }
    refresh();
  };

  const s = makeStyles(c);

  if (!status) {
    return <View style={[s.screen, { paddingTop: insets.top }]} />;
  }

  const steps = [
    status.micGranted,
    status.keyboardEnabled,
    status.keyboardSelected,
  ];
  const current = steps.indexOf(false); // -1 when everything is set up

  return (
    <ScrollView
      style={s.screen}
      contentContainerStyle={[
        s.content,
        { paddingTop: insets.top + 24, paddingBottom: insets.bottom + 32 },
      ]}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={s.title}>Sayflux</Text>
      <Text style={s.subtitle}>
        Speak instead of typing, in any app. Your voice is turned into text on
        your phone and never leaves it.
      </Text>

      <Step
        c={c}
        n={1}
        done={status.micGranted}
        active={current === 0}
        title="Allow the microphone"
        body="Sayflux only listens after you tap the mic on its keyboard, until you tap it again."
      >
        <Button c={c} label="Allow microphone" onPress={requestMic} />
      </Step>

      <Step
        c={c}
        n={2}
        done={status.keyboardEnabled}
        active={current === 1}
        title="Turn on the Sayflux keyboard"
        body="In the list that opens, switch on Sayflux. Android warns that a keyboard can see what you type; Sayflux only types text you dictate and stores none of it."
      >
        <Button
          c={c}
          label="Open keyboard settings"
          onPress={() => Sayflux.openKeyboardSettings()}
        />
      </Step>

      <Step
        c={c}
        n={3}
        done={status.keyboardSelected}
        active={current === 2}
        title="Switch to Sayflux"
        body="Pick Sayflux as your keyboard. Its keyboard button switches back to your usual one whenever you want to type."
      >
        <Button
          c={c}
          label="Choose keyboard"
          onPress={() => Sayflux.showKeyboardPicker()}
        />
      </Step>

      <TryIt c={c} />
      <CleanupSettings c={c} />
    </ScrollView>
  );
}

function Step({
  c,
  n,
  done,
  active,
  title,
  body,
  children,
}: {
  c: Palette;
  n: number;
  done: boolean;
  active: boolean;
  title: string;
  body: string;
  children: React.ReactNode;
}) {
  const s = makeStyles(c);
  return (
    <View style={[s.card, active && { borderColor: c.accent }]}>
      <View style={s.stepHead}>
        <View
          style={[
            s.badge,
            done && { backgroundColor: c.done, borderColor: c.done },
            active && { borderColor: c.accent },
          ]}
        >
          <Text style={[s.badgeText, done && s.badgeTextDone]}>
            {done ? '✓' : n}
          </Text>
        </View>
        <Text style={[s.stepTitle, done && { color: c.muted }]}>{title}</Text>
      </View>
      {!done && (
        <View style={s.stepBody}>
          <Text style={s.body}>{body}</Text>
          {active ? children : null}
        </View>
      )}
    </View>
  );
}

function TryIt({ c }: { c: Palette }) {
  const s = makeStyles(c);
  const [text, setText] = useState('');
  return (
    <View style={s.card}>
      <Text style={s.sectionTitle}>Try it</Text>
      <Text style={s.body}>
        Tap the box, then tap the mic on the Sayflux keyboard. Tap again when
        you're done speaking.
      </Text>
      <TextInput
        style={[s.input, s.multiline]}
        value={text}
        onChangeText={setText}
        multiline
        placeholder="Your words will appear here"
        placeholderTextColor={c.muted}
      />
    </View>
  );
}

function CleanupSettings({ c }: { c: Palette }) {
  const s = makeStyles(c);
  const [settings, setSettings] = useState<Settings>(() =>
    Sayflux.getSettings(),
  );
  const [saved, setSaved] = useState(true);

  const update = (patch: Partial<Settings>, saveNow = false) => {
    const next = { ...settings, ...patch };
    setSettings(next);
    if (saveNow) {
      Sayflux.saveSettings(next);
    } else {
      setSaved(false);
    }
  };

  const save = () => {
    const next = {
      ...settings,
      apiKey: settings.apiKey.trim(),
      model: settings.model.trim(),
    };
    setSettings(next);
    Sayflux.saveSettings(next);
    setSaved(true);
  };

  return (
    <View style={s.card}>
      <Text style={s.sectionTitle}>Clean-up</Text>

      <View style={s.switchRow}>
        <View style={s.switchText}>
          <Text style={s.label}>Basic clean-up</Text>
          <Text style={s.hint}>
            On your phone: removes "um" and stutters like "the the", applies
            "scratch that", and turns "new line" into a line break.
          </Text>
        </View>
        <Switch
          value={settings.basicCleanup}
          onValueChange={v => update({ basicCleanup: v }, true)}
          trackColor={{ true: c.accent, false: c.track }}
        />
      </View>

      <View style={s.switchRow}>
        <View style={s.switchText}>
          <Text style={s.label}>Smart clean-up with Gemini</Text>
          <Text style={s.hint}>
            Applies corrections like "Tuesday, no, Wednesday", formats lists and
            writes numbers and emails properly. Only the text is sent, never
            your voice. Needs an API key.
          </Text>
        </View>
        <Switch
          value={settings.smartCleanup}
          onValueChange={v => update({ smartCleanup: v }, true)}
          trackColor={{ true: c.accent, false: c.track }}
        />
      </View>

      <Text style={s.label}>Gemini API key</Text>
      <TextInput
        style={s.input}
        value={settings.apiKey}
        onChangeText={v => update({ apiKey: v })}
        placeholder="Paste your key"
        placeholderTextColor={c.muted}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
      />
      <Pressable
        onPress={() => Linking.openURL('https://aistudio.google.com/apikey')}
      >
        <Text style={s.link}>Get a free key from Google AI Studio</Text>
      </Pressable>

      <Text style={[s.label, s.gapTop]}>Model</Text>
      <TextInput
        style={s.input}
        value={settings.model}
        onChangeText={v => update({ model: v })}
        autoCapitalize="none"
        autoCorrect={false}
      />

      <Text style={[s.label, s.gapTop]}>Extra instructions (optional)</Text>
      <TextInput
        style={[s.input, s.multiline]}
        value={settings.instructions}
        onChangeText={v => update({ instructions: v })}
        multiline
        placeholder="Names and words to spell correctly, e.g. Mesum, Sayflux"
        placeholderTextColor={c.muted}
      />
      <Button
        c={c}
        label={saved ? 'Saved' : 'Save'}
        disabled={saved}
        onPress={save}
      />
    </View>
  );
}

function Button({
  c,
  label,
  onPress,
  disabled = false,
}: {
  c: Palette;
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  const s = makeStyles(c);
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        s.button,
        disabled && s.disabled,
        pressed && s.pressed,
      ]}
    >
      <Text style={s.buttonText}>{label}</Text>
    </Pressable>
  );
}

const styleCache = new Map<Palette, ReturnType<typeof buildStyles>>();

function makeStyles(c: Palette) {
  let styles = styleCache.get(c);
  if (!styles) {
    styles = buildStyles(c);
    styleCache.set(c, styles);
  }
  return styles;
}

function buildStyles(c: Palette) {
  return StyleSheet.create({
    screen: { flex: 1, backgroundColor: c.bg },
    content: { paddingHorizontal: 16 },
    title: { fontSize: 32, fontWeight: '700', color: c.text },
    subtitle: {
      fontSize: 16,
      lineHeight: 23,
      color: c.muted,
      marginTop: 6,
      marginBottom: 20,
    },
    card: {
      backgroundColor: c.card,
      borderRadius: 16,
      borderWidth: 1.5,
      borderColor: c.border,
      padding: 16,
      marginBottom: 12,
    },
    stepHead: { flexDirection: 'row', alignItems: 'center' },
    badge: {
      width: 28,
      height: 28,
      borderRadius: 14,
      borderWidth: 1.5,
      borderColor: c.border,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: 12,
    },
    badgeText: { fontSize: 14, fontWeight: '700', color: c.text },
    badgeTextDone: { color: '#FFFFFF' },
    stepTitle: { flex: 1, fontSize: 17, fontWeight: '600', color: c.text },
    stepBody: { marginTop: 10, marginLeft: 40 },
    sectionTitle: {
      fontSize: 17,
      fontWeight: '600',
      color: c.text,
      marginBottom: 10,
    },
    body: { fontSize: 15, lineHeight: 21, color: c.muted, marginBottom: 12 },
    label: { fontSize: 15, fontWeight: '500', color: c.text, marginBottom: 4 },
    hint: { fontSize: 14, lineHeight: 19, color: c.muted },
    switchRow: {
      flexDirection: 'row',
      alignItems: 'center',
      marginBottom: 14,
    },
    switchText: { flex: 1, marginRight: 12 },
    link: { fontSize: 14, color: c.accent, marginTop: 6 },
    gapTop: { marginTop: 10 },
    input: {
      borderWidth: 1,
      borderColor: c.border,
      borderRadius: 10,
      paddingHorizontal: 12,
      paddingVertical: 10,
      fontSize: 16,
      color: c.text,
      backgroundColor: c.bg,
      marginBottom: 4,
    },
    multiline: { minHeight: 96, textAlignVertical: 'top', marginBottom: 12 },
    button: {
      backgroundColor: c.accent,
      borderRadius: 12,
      paddingVertical: 12,
      paddingHorizontal: 18,
      alignItems: 'center',
      alignSelf: 'flex-start',
      marginTop: 4,
    },
    buttonText: { fontSize: 16, fontWeight: '600', color: c.accentText },
    disabled: { opacity: 0.45 },
    pressed: { opacity: 0.75 },
  });
}

export default App;
