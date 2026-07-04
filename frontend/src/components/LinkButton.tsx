import type { ButtonHTMLAttributes } from 'react';

interface LinkButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    /** primary = link blue (default), danger = destructive red. */
    variant?: 'primary' | 'danger';
}

/**
 * Borderless inline text button styled like a link — the standard
 * Edit/Delete/View action inside cards and table rows.
 */
export default function LinkButton({ variant = 'primary', style, ...rest }: LinkButtonProps) {
    return (
        <button
            style={{
                background: 'none',
                border: 'none',
                color: variant === 'danger' ? '#d32f2f' : '#1976d2',
                cursor: 'pointer',
                fontSize: '0.85rem',
                ...style,
            }}
            {...rest}
        />
    );
}
