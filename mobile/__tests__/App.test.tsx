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
jest.mock('../specs/NativeSayflux', () => ({
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
      model: 'gemini-3.1-flash-lite',
      instructions: '',
    }),
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
  expect(text).toContain('Turn on the Sayflux keyboard');
  expect(text).toContain('Gemini API key');
  await ReactTestRenderer.act(async () => tree!.unmount());
});
