/**
 * @format
 */

import React from 'react';
import { render } from '@testing-library/react-native';
import App from '../App';

describe('App', () => {
  it('renders the sample amount formatted as currency', () => {
    const { getByText } = render(<App />);
    expect(getByText('$1,234,567.89')).toBeTruthy();
  });
});
