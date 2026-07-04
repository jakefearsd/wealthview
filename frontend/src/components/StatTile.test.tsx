import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import StatTile from './StatTile';

describe('StatTile', () => {
    it('renders the label above the value', () => {
        render(<StatTile label="Essential" value="$40,000" />);

        expect(screen.getByText('Essential')).toBeInTheDocument();
        expect(screen.getByText('$40,000')).toBeInTheDocument();
    });

    it('styles the label as small muted text', () => {
        render(<StatTile label="Essential" value="$40,000" />);

        const label = screen.getByText('Essential');
        expect(label).toHaveStyle({ color: '#999', fontSize: '0.75rem' });
    });

    it('defaults the value color to #444', () => {
        render(<StatTile label="Essential" value="$40,000" />);

        expect(screen.getByText('$40,000')).toHaveStyle({ color: '#444' });
    });

    it('applies a custom valueColor', () => {
        render(<StatTile label="Net" value="+$1,200" valueColor="#2e7d32" />);

        expect(screen.getByText('+$1,200')).toHaveStyle({ color: '#2e7d32' });
    });

    it('merges valueStyle overrides onto the value line', () => {
        render(<StatTile label="Value" value="$500,000" valueStyle={{ fontSize: '1.3rem', fontWeight: 700 }} />);

        expect(screen.getByText('$500,000')).toHaveStyle({ fontSize: '1.3rem', fontWeight: 700 });
    });

    it('sets the title attribute on the tile when provided', () => {
        render(<StatTile label="Trials" value="5,000" title="Monte Carlo trial count" />);

        expect(screen.getByTitle('Monte Carlo trial count')).toBeInTheDocument();
    });
});
