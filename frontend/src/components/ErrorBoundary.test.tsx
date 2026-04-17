import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ErrorBoundary from './ErrorBoundary';

function ThrowingChild(): null {
    throw new Error('boom');
}

describe('ErrorBoundary', () => {
    let originalConsoleError: typeof console.error;
    beforeEach(() => {
        originalConsoleError = console.error;
        console.error = vi.fn();
    });
    afterEach(() => {
        console.error = originalConsoleError;
    });

    it('renders children when there is no error', () => {
        render(
            <ErrorBoundary>
                <div>healthy</div>
            </ErrorBoundary>
        );
        expect(screen.getByText('healthy')).toBeInTheDocument();
    });

    it('shows the fallback UI when a child throws', () => {
        render(
            <ErrorBoundary>
                <ThrowingChild />
            </ErrorBoundary>
        );
        expect(screen.getByText('Something went wrong')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Reload Page' })).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Go to Dashboard' })).toBeInTheDocument();
    });
});
