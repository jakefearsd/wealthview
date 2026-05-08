import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ChartTooltip from './ChartTooltip';
import type { RechartsTooltipEntry } from '../types/recharts';

interface Row {
    label: string;
    amount: number;
}

function payload(): Array<RechartsTooltipEntry<Row>> {
    return [
        { name: 'A', value: 100, color: '#000', payload: { label: 'A', amount: 100 } },
        { name: 'B', value: 50, color: '#111', payload: { label: 'B', amount: 50 } },
    ];
}

describe('ChartTooltip', () => {
    it('returns null when not active', () => {
        const renderContent = vi.fn();
        const { container } = render(
            <ChartTooltip<Row>
                active={false}
                payload={payload()}
                label="2030"
                renderContent={renderContent}
            />,
        );

        expect(container.innerHTML).toBe('');
        expect(renderContent).not.toHaveBeenCalled();
    });

    it('returns null when active but the payload is empty', () => {
        const renderContent = vi.fn();
        const { container } = render(
            <ChartTooltip<Row>
                active
                payload={[]}
                label="2030"
                renderContent={renderContent}
            />,
        );

        expect(container.innerHTML).toBe('');
        expect(renderContent).not.toHaveBeenCalled();
    });

    it('returns null when active but payload is undefined', () => {
        const renderContent = vi.fn();
        const { container } = render(
            <ChartTooltip<Row> active label="2030" renderContent={renderContent} />,
        );

        expect(container.innerHTML).toBe('');
        expect(renderContent).not.toHaveBeenCalled();
    });

    it('passes label and payload to renderContent and wraps the result', () => {
        const renderContent = vi.fn(
            (label, items) => (
                <span data-testid="content">
                    {`${label} -> ${items.length} items`}
                </span>
            ),
        );
        render(
            <ChartTooltip<Row>
                active
                payload={payload()}
                label={2030}
                renderContent={renderContent}
            />,
        );

        expect(renderContent).toHaveBeenCalledWith(2030, payload());
        expect(screen.getByTestId('content')).toHaveTextContent('2030 -> 2 items');
    });

    it('returns null when renderContent yields null', () => {
        const renderContent = vi.fn(() => null);
        const { container } = render(
            <ChartTooltip<Row>
                active
                payload={payload()}
                label="x"
                renderContent={renderContent}
            />,
        );

        expect(container.innerHTML).toBe('');
    });
});
