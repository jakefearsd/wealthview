import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import VisuallyHidden from './VisuallyHidden';

describe('VisuallyHidden', () => {
    it('keeps the text available to assistive technology', () => {
        render(<table><thead><tr><th><VisuallyHidden>Actions</VisuallyHidden></th></tr></thead></table>);

        expect(screen.getByRole('columnheader', { name: 'Actions' })).toBeInTheDocument();
    });
});
