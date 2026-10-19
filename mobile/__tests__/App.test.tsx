/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);

// The real module is native Kotlin code; stand in for it with a fresh install's state.
jest.mock('../specs/NativeWhistleflux', () => ({
  __esModule: true,
  default: {
    getStatus: () =>
      Promise.resolve({
        micGranted: false,
        keyboardEnabled: false,
        keyboardSelected: false,
      }),
    getSettings: () => ({
      basicCleanup: true,
      smartCleanup: true,
      apiKey: '',
      model: 'gemini-3.5-flash-lite',
      instructions: '',
    }),
    getModels: () => [
      {
        id: 'gemini-3.5-flash-lite',
        label: 'Gemini 3.5 Flash-Lite',
        note: 'Fast',
      },
      { id: 'gemini-3.5-flash', label: 'Gemini 3.5 Flash', note: 'Stronger' },
    ],
    saveSettings: jest.fn(),
    openKeyboardSettings: jest.fn(),
    showKeyboardPicker: jest.fn(),
  },
}));

test('shows the setup steps on a fresh install', async () => {
  let tree: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  const text = JSON.stringify(tree!.toJSON());
  expect(text).toContain('Allow the microphone');
  expect(text).toContain('Turn on the Whistleflux keyboard');
  expect(text).toContain('Gemini API key');
  await ReactTestRenderer.act(async () => tree!.unmount());
});

test('picks the model from a list instead of typing it', async () => {
  const native = require('../specs/NativeWhistleflux').default;
  let tree: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  const root = tree!.root;
  expect(JSON.stringify(tree!.toJSON())).toContain('Gemini 3.5 Flash-Lite');

  const pressable = (id: string) =>
    root.find(
      n => n.props.testID === id && typeof n.props.onPress === 'function',
    );
  await ReactTestRenderer.act(async () =>
    pressable('model-picker').props.onPress(),
  );
  await ReactTestRenderer.act(async () =>
    pressable('model-gemini-3.5-flash').props.onPress(),
  );
  expect(native.saveSettings).toHaveBeenLastCalledWith(
    expect.objectContaining({ model: 'gemini-3.5-flash' }),
  );
  await ReactTestRenderer.act(async () => tree!.unmount());
});
