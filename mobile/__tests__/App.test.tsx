/**
 * @format
 */

import React from 'react';
import { render, waitFor } from '@testing-library/react-native';
import App from '../App';

describe('App', () => {
  it('mounts and lands on the server-config screen when nothing is in keychain', async () => {
    // The default jest.setup.js stub returns `false` for getGenericPassword,
    // so the AuthProvider sees no server URL and the navigator routes to
    // ServerConfigScreen on first frame.
    const { findByTestId } = await render(<App />);

    await waitFor(async () => {
      expect(await findByTestId('server-url-input')).toBeTruthy();
    });
  });
});
