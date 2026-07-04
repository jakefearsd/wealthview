import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import LinkButton from './LinkButton';

describe('LinkButton', () => {
    it('renders a borderless inline button in the primary link color', () => {
        render(<LinkButton>Edit</LinkButton>);

        const button = screen.getByRole('button', { name: 'Edit' });
        expect(button).toHaveStyle({ background: 'none', color: '#1976d2', cursor: 'pointer' });
        expect(button.style.borderStyle).toBe('none');
        expect(button.style.fontSize).toBe('0.85rem');
    });

    it('uses the danger color for variant="danger"', () => {
        render(<LinkButton variant="danger">Delete</LinkButton>);

        expect(screen.getByRole('button', { name: 'Delete' })).toHaveStyle({ color: '#d32f2f' });
    });

    it('fires onClick', async () => {
        const onClick = vi.fn();
        render(<LinkButton onClick={onClick}>Edit</LinkButton>);

        await userEvent.click(screen.getByRole('button', { name: 'Edit' }));

        expect(onClick).toHaveBeenCalledTimes(1);
    });

    it('merges style overrides over the defaults', () => {
        render(<LinkButton style={{ color: '#7c3aed', opacity: 0.5 }}>Re-optimize</LinkButton>);

        expect(screen.getByRole('button', { name: 'Re-optimize' })).toHaveStyle({ color: '#7c3aed', opacity: 0.5 });
    });

    it('passes through button attributes like disabled', () => {
        render(<LinkButton disabled>Edit</LinkButton>);

        expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
    });
});
