import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import DisclaimerBox from './DisclaimerBox';

describe('DisclaimerBox', () => {
    it('render_withTestIdAndMargin_showsChildrenInTheSharedAmberBox', () => {
        render(<DisclaimerBox testId="d" margin="1rem 0 0">Model, not a forecast</DisclaimerBox>);

        const box = screen.getByTestId('d');
        expect(box).toHaveTextContent('Model, not a forecast');
        expect(box).toHaveStyle({ background: '#fff3e0', margin: '1rem 0px 0px' });
    });
});
