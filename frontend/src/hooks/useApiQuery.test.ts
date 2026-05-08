import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useApiQuery } from './useApiQuery';

describe('useApiQuery', () => {
    it('starts in loading=true with null data and error', async () => {
        const fetchFn = vi.fn().mockResolvedValue('result');
        const { result } = renderHook(() => useApiQuery(fetchFn));

        expect(result.current.loading).toBe(true);
        expect(result.current.data).toBeNull();
        expect(result.current.error).toBeNull();
        await waitFor(() => expect(result.current.loading).toBe(false));
    });

    it('exposes the resolved value once the promise settles', async () => {
        const fetchFn = vi.fn().mockResolvedValue({ id: 'abc' });
        const { result } = renderHook(() => useApiQuery(fetchFn));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.data).toEqual({ id: 'abc' });
        expect(result.current.error).toBeNull();
        expect(fetchFn).toHaveBeenCalledTimes(1);
    });

    it('reads response.data.message from axios-shaped errors', async () => {
        const fetchFn = vi
            .fn()
            .mockRejectedValue({ response: { data: { message: 'Forbidden' } } });
        const { result } = renderHook(() => useApiQuery(fetchFn));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBe('Forbidden');
        expect(result.current.data).toBeNull();
    });

    it('falls back to err.message when no response body is present', async () => {
        const fetchFn = vi.fn().mockRejectedValue(new Error('network down'));
        const { result } = renderHook(() => useApiQuery(fetchFn));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBe('network down');
    });

    it('uses the catch-all string when neither response nor message exists', async () => {
        const fetchFn = vi.fn().mockRejectedValue({});
        const { result } = renderHook(() => useApiQuery(fetchFn));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBe('An error occurred');
    });

    it('refetch re-runs the supplied fetch function and clears prior error state', async () => {
        let value = 1;
        const fetchFn = vi.fn(() => Promise.resolve(value));
        const { result } = renderHook(() => useApiQuery<number>(fetchFn));

        await waitFor(() => expect(result.current.data).toBe(1));

        value = 2;
        act(() => result.current.refetch());
        // The hook flips loading=true synchronously when refetch fires.
        expect(result.current.loading).toBe(true);

        await waitFor(() => expect(result.current.data).toBe(2));
        expect(fetchFn).toHaveBeenCalledTimes(2);
    });

    it('ignores a stale resolution after unmount (no state update warning)', async () => {
        let resolveFn: (v: string) => void = () => {};
        const fetchFn = vi.fn(
            () => new Promise<string>((resolve) => {
                resolveFn = resolve;
            }),
        );

        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
        const { unmount } = renderHook(() => useApiQuery(fetchFn));

        unmount();
        resolveFn('after unmount');

        // Give the microtask queue a tick to drain.
        await Promise.resolve();
        await Promise.resolve();

        expect(errorSpy).not.toHaveBeenCalled();
        errorSpy.mockRestore();
    });
});
