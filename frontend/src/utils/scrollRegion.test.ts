import { describe, it, expect } from 'vitest';
import { scrollRegionProps } from './scrollRegion';

describe('scrollRegionProps', () => {
    it('returns a focusable, named region', () => {
        expect(scrollRegionProps('Accounts table')).toEqual({ role: 'region', tabIndex: 0, 'aria-label': 'Accounts table' });
    });
});
