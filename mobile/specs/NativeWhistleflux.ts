// Interface to the native Kotlin code (keyboard and settings).
// React Native's codegen turns this file into the NativeWhistlefluxSpec class on Android.
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type Status = {
  micGranted: boolean;
  keyboardEnabled: boolean;
  keyboardSelected: boolean;
};

export type Settings = {
  basicCleanup: boolean;
  smartCleanup: boolean;
  apiKey: string;
  model: string;
  instructions: string;
};

export type ModelOption = {
  id: string;
  label: string;
  note: string;
};

export interface Spec extends TurboModule {
  getStatus(): Promise<Status>;
  getSettings(): Settings;
  getModels(): Array<ModelOption>;
  saveSettings(settings: Settings): void;
  /** Deletes what the keyboard learned from your typing (kept only on this phone). */
  forgetLearnedWords(): void;
  openKeyboardSettings(): void;
  showKeyboardPicker(): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Whistleflux');
