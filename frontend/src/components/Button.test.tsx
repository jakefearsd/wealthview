import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import Button from './Button';

function rgbToHex(rgb: string): string {
    const [r, g, b] = rgb.match(/\d+/g)!.map(Number);
    return `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`;
}

function contrastWithWhite(hex: string): number {
    const channel = (offset: number) => {
        const c = parseInt(hex.slice(offset, offset + 2), 16) / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    const luminance = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
    return 1.05 / (luminance + 0.05);
}

describe('Button', () => {
    it('defaults to the primary variant', () => {
        render(<Button>Save</Button>);

        expect(screen.getByRole('button', { name: 'Save' })).toHaveStyle({
            background: '#1976d2',
            color: '#fff',
        });
    });

    it('renders the danger variant', () => {
        render(<Button variant="danger">Delete</Button>);

        expect(screen.getByRole('button', { name: 'Delete' })).toHaveStyle({ background: '#d32f2f' });
    });

    it('renders the warning variant in dark amber with white text', () => {
        render(<Button variant="warning">Edit</Button>);

        expect(screen.getByRole('button', { name: 'Edit' })).toHaveStyle({
            background: '#b45309',
            color: '#fff',
        });
    });

    it.each(['primary', 'danger', 'warning', 'neutral'] as const)(
        'gives the %s variant at least 4.5:1 contrast for white text (WCAG AA)',
        (variant) => {
            render(<Button variant={variant}>Go</Button>);

            const bg = screen.getByRole('button', { name: 'Go' }).style.background;
            const hex = bg.startsWith('#') ? bg : rgbToHex(bg);

            expect(contrastWithWhite(hex)).toBeGreaterThanOrEqual(4.5);
        },
    );

    it('renders the neutral variant in gray', () => {
        render(<Button variant="neutral">Cancel Edit</Button>);

        expect(screen.getByRole('button', { name: 'Cancel Edit' })).toHaveStyle({
            background: '#757575',
            color: '#fff',
        });
    });

    it('dims and blocks interaction when disabled', () => {
        render(<Button disabled>Save</Button>);

        const button = screen.getByRole('button', { name: 'Save' });
        expect(button).toBeDisabled();
        expect(button).toHaveStyle({ opacity: 0.5 });
    });
});
