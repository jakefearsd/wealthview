import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import TabBar from './TabBar';

const TABS = [
    { key: 'chart', label: 'Balance Over Time' },
    { key: 'flows', label: 'Annual Flows' },
    { key: 'table', label: 'Data Table' },
] as const;

describe('TabBar', () => {
    it('renders one tab per tab entry', () => {
        render(<TabBar tabs={TABS} active="chart" onSelect={() => {}} />);

        expect(screen.getAllByRole('tab')).toHaveLength(3);
        expect(screen.getByRole('tab', { name: 'Annual Flows' })).toBeInTheDocument();
    });

    it('highlights the active tab and mutes the others', () => {
        render(<TabBar tabs={TABS} active="flows" onSelect={() => {}} />);

        expect(screen.getByRole('tab', { name: 'Annual Flows' })).toHaveStyle({
            color: '#1976d2',
            fontWeight: 600,
        });
        expect(screen.getByRole('tab', { name: 'Data Table' })).toHaveStyle({
            color: '#666',
            fontWeight: 400,
        });
    });

    it('calls onSelect with the clicked tab key', async () => {
        const onSelect = vi.fn();
        render(<TabBar tabs={TABS} active="chart" onSelect={onSelect} />);

        await userEvent.click(screen.getByRole('tab', { name: 'Data Table' }));

        expect(onSelect).toHaveBeenCalledWith('table');
    });

    it('merges style overrides onto the container', () => {
        const { container } = render(
            <TabBar tabs={TABS} active="chart" onSelect={() => {}} style={{ marginBottom: '1.5rem' }} />
        );

        expect(container.firstChild).toHaveStyle({ marginBottom: '1.5rem', borderBottom: '1px solid #e0e0e0' });
    });

    it('exposes a tablist with aria-selected on the active tab only', () => {
        render(<TabBar tabs={TABS} active="flows" onSelect={() => {}} />);

        expect(screen.getByRole('tablist')).toBeInTheDocument();
        expect(screen.getByRole('tab', { name: 'Annual Flows' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('tab', { name: 'Data Table' })).toHaveAttribute('aria-selected', 'false');
    });

    it('scrolls horizontally instead of overflowing a narrow window', () => {
        render(<TabBar tabs={TABS} active="chart" onSelect={() => {}} />);

        expect(screen.getByRole('tablist')).toHaveStyle({ overflowX: 'auto' });
    });

    it('keeps tab labels on one line', () => {
        render(<TabBar tabs={TABS} active="chart" onSelect={() => {}} />);

        expect(screen.getByRole('tab', { name: 'Balance Over Time' })).toHaveStyle({ whiteSpace: 'nowrap' });
    });
});
