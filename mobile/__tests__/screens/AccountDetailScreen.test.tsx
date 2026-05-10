/**
 * @format
 */

import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';

const mockGoBack = jest.fn();

const ROUTE_PARAMS = {
    account: {
        id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        name: 'Fidelity Brokerage',
        type: 'brokerage',
        institution: 'Fidelity',
        currency: 'USD',
        balance: '456789.00',
        created_at: '2026-01-01T00:00:00Z',
    },
};

jest.mock('@react-navigation/native', () => ({
    __esModule: true,
    useNavigation: () => ({ goBack: mockGoBack }),
    useRoute: () => ({ params: ROUTE_PARAMS }),
}));

import { AccountDetailScreen } from '../../src/screens/AccountDetailScreen';

beforeEach(() => {
    jest.clearAllMocks();
});

describe('AccountDetailScreen', () => {
    it('renders the account name as the title', () => {
        const { queryByText } = render(<AccountDetailScreen />);
        expect(queryByText('Fidelity Brokerage')).toBeTruthy();
    });

    it('shows the institution, type label, and currency', () => {
        const { queryAllByText, queryByText } = render(<AccountDetailScreen />);
        // "Fidelity" appears in both the title ("Fidelity Brokerage") and the
        // institution row, so use queryAllByText for that one.
        expect(queryAllByText(/Fidelity/).length).toBeGreaterThanOrEqual(2);
        expect(queryByText(/^Brokerage$/)).toBeTruthy();
        expect(queryByText(/USD/)).toBeTruthy();
    });

    it('renders the formatted balance', () => {
        const { queryByText } = render(<AccountDetailScreen />);
        expect(queryByText(/\$456,789\.00/)).toBeTruthy();
    });

    it('shows a "more details coming soon" placeholder', () => {
        const { queryByText } = render(<AccountDetailScreen />);
        expect(queryByText(/coming soon/i)).toBeTruthy();
    });

    it('calls navigation.goBack when the back button is pressed', () => {
        const { getByTestId } = render(<AccountDetailScreen />);
        fireEvent.press(getByTestId('back-button'));
        expect(mockGoBack).toHaveBeenCalled();
    });
});
