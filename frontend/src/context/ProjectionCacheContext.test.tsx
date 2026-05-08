import { render, renderHook, screen } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ProjectionCacheProvider, useProjectionCache } from './ProjectionCacheContext';
import type { ProjectionResult } from '../types/projection';

const STORAGE_KEY = 'projection_cache';

function makeResult(id: string): ProjectionResult {
    return {
        scenario_id: id,
        yearly_data: [],
        final_balance: 1_000_000,
        years_in_retirement: 0,
        spending_feasibility: null,
    };
}

function wrapper({ children }: { children: React.ReactNode }) {
    return <ProjectionCacheProvider>{children}</ProjectionCacheProvider>;
}

describe('ProjectionCacheProvider', () => {
    beforeEach(() => {
        sessionStorage.clear();
    });

    it('returns null when nothing is cached for the given id', () => {
        const { result } = renderHook(() => useProjectionCache(), { wrapper });

        expect(result.current.get('missing')).toBeNull();
    });

    it('stores a result and returns it on subsequent get calls', () => {
        const { result } = renderHook(() => useProjectionCache(), { wrapper });
        const projection = makeResult('p1');

        result.current.set('p1', projection);

        expect(result.current.get('p1')).toEqual(projection);
    });

    it('persists the cache to sessionStorage on set', () => {
        const { result } = renderHook(() => useProjectionCache(), { wrapper });
        result.current.set('p1', makeResult('p1'));

        const raw = sessionStorage.getItem(STORAGE_KEY);
        expect(raw).not.toBeNull();
        const parsed = JSON.parse(raw!) as [string, ProjectionResult][];
        expect(parsed[0][0]).toBe('p1');
    });

    it('rehydrates from sessionStorage when a new provider mounts', () => {
        sessionStorage.setItem(
            STORAGE_KEY,
            JSON.stringify([['existing', makeResult('existing')]]),
        );

        const { result } = renderHook(() => useProjectionCache(), { wrapper });

        expect(result.current.get('existing')).not.toBeNull();
        expect(result.current.get('existing')?.scenario_id).toBe('existing');
    });

    it('falls back to an empty cache when sessionStorage is corrupt JSON', () => {
        sessionStorage.setItem(STORAGE_KEY, '{not valid json');

        const { result } = renderHook(() => useProjectionCache(), { wrapper });

        expect(result.current.get('anything')).toBeNull();
    });

    it('clear removes the entry from the cache and from storage', () => {
        const { result } = renderHook(() => useProjectionCache(), { wrapper });
        result.current.set('p1', makeResult('p1'));
        result.current.set('p2', makeResult('p2'));

        result.current.clear('p1');

        expect(result.current.get('p1')).toBeNull();
        expect(result.current.get('p2')).not.toBeNull();
        const raw = sessionStorage.getItem(STORAGE_KEY);
        const parsed = JSON.parse(raw!) as [string, ProjectionResult][];
        expect(parsed.map(([id]) => id)).toEqual(['p2']);
    });
});

describe('useProjectionCache', () => {
    it('throws a clear message when used outside the provider', () => {
        const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
        try {
            function Bad() {
                useProjectionCache();
                return null;
            }
            expect(() => render(<Bad />)).toThrow(
                /useProjectionCache must be used within ProjectionCacheProvider/,
            );
        } finally {
            spy.mockRestore();
        }
    });

    it('exposes the same cache instance to multiple consumers under one provider', () => {
        function Producer() {
            const cache = useProjectionCache();
            cache.set('shared', makeResult('shared'));
            return null;
        }
        function Reader() {
            const cache = useProjectionCache();
            const hit = cache.get('shared');
            return <div data-testid="hit">{hit ? 'present' : 'missing'}</div>;
        }

        render(
            <ProjectionCacheProvider>
                <Producer />
                <Reader />
            </ProjectionCacheProvider>,
        );

        expect(screen.getByTestId('hit').textContent).toBe('present');
    });
});
