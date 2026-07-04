import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import Button from './Button';

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

    it('renders the warning variant in orange', () => {
        render(<Button variant="warning">Edit</Button>);

        expect(screen.getByRole('button', { name: 'Edit' })).toHaveStyle({
            background: '#ff9800',
            color: '#fff',
        });
    });

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
