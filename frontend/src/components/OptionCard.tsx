import type { KeyboardEvent, ReactNode } from 'react';

interface OptionCardProps {
    selected: boolean;
    onSelect: () => void;
    /** Inner padding; the tax-treatment cards are a little tighter than the strategy cards. */
    padding?: string;
    children: ReactNode;
}

/**
 * A selectable card that behaves as a radio: reachable by keyboard, announced as a radio with its
 * checked state, and selectable with Enter or Space. Render inside an element with
 * `role="radiogroup"` and an accessible name.
 */
export default function OptionCard({ selected, onSelect, padding = '1rem', children }: OptionCardProps) {
    function handleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onSelect();
        }
    }

    return (
        <div
            role="radio"
            aria-checked={selected}
            tabIndex={0}
            onClick={onSelect}
            onKeyDown={handleKeyDown}
            style={{
                border: `2px solid ${selected ? '#1976d2' : '#e0e0e0'}`,
                background: selected ? '#e3f2fd' : '#fff',
                cursor: 'pointer',
                borderRadius: '8px',
                padding,
            }}
        >
            {children}
        </div>
    );
}
