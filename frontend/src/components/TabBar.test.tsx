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
    it('renders one button per tab', () => {
        render(<TabBar tabs={TABS} active="chart" onSelect={() => {}} />);

        expect(screen.getAllByRole('button')).toHaveLength(3);
        expect(screen.getByRole('button', { name: 'Annual Flows' })).toBeInTheDocument();
    });

    it('highlights the active tab and mutes the others', () => {
        render(<TabBar tabs={TABS} active="flows" onSelect={() => {}} />);

        expect(screen.getByRole('button', { name: 'Annual Flows' })).toHaveStyle({
            color: '#1976d2',
            fontWeight: 600,
        });
        expect(screen.getByRole('button', { name: 'Data Table' })).toHaveStyle({
            color: '#666',
            fontWeight: 400,
        });
    });

    it('calls onSelect with the clicked tab key', async () => {
        const onSelect = vi.fn();
        render(<TabBar tabs={TABS} active="chart" onSelect={onSelect} />);

        await userEvent.click(screen.getByRole('button', { name: 'Data Table' }));

        expect(onSelect).toHaveBeenCalledWith('table');
    });

    it('merges style overrides onto the container', () => {
        const { container } = render(
            <TabBar tabs={TABS} active="chart" onSelect={() => {}} style={{ marginBottom: '1.5rem' }} />
        );

        expect(container.firstChild).toHaveStyle({ marginBottom: '1.5rem', borderBottom: '1px solid #e0e0e0' });
    });
});
