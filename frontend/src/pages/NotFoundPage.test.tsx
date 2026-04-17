import { screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { renderWithRouter } from '../test-utils';
import NotFoundPage from './NotFoundPage';

describe('NotFoundPage', () => {
    it('renders 404 and a dashboard link', () => {
        renderWithRouter(<NotFoundPage />);
        expect(screen.getByText('404')).toBeInTheDocument();
        expect(screen.getByText('Page not found')).toBeInTheDocument();
        const link = screen.getByRole('link', { name: 'Go to Dashboard' });
        expect(link).toHaveAttribute('href', '/');
    });
});
