import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect } from 'vitest';
import InfoSection from './InfoSection';

describe('InfoSection', () => {
    it('is collapsed by default', () => {
        render(<InfoSection>Detailed explanation here.</InfoSection>);
        expect(screen.queryByText('Detailed explanation here.')).not.toBeInTheDocument();
        expect(screen.getByText('What is this?')).toBeInTheDocument();
    });

    it('expands when clicked', async () => {
        render(<InfoSection prompt="Learn more">Detailed explanation here.</InfoSection>);
        await userEvent.click(screen.getByText('Learn more'));
        expect(screen.getByText('Detailed explanation here.')).toBeInTheDocument();
    });

    it('collapses when clicked again', async () => {
        render(<InfoSection>Detailed explanation here.</InfoSection>);
        await userEvent.click(screen.getByText('What is this?'));
        expect(screen.getByText('Detailed explanation here.')).toBeInTheDocument();
        await userEvent.click(screen.getByText('Hide'));
        expect(screen.queryByText('Detailed explanation here.')).not.toBeInTheDocument();
    });
});
