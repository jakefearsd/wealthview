import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import toast from 'react-hot-toast';
import { useApiMutation } from './useApiMutation';

vi.mock('react-hot-toast', () => ({
    default: {
        success: vi.fn(),
        error: vi.fn(),
    },
}));

vi.mock('../utils/errorMessage', () => ({
    extractErrorMessage: vi.fn((err: unknown) => (err as Error).message ?? 'Unknown error'),
}));

describe('useApiMutation', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('initializes with loading=false, error=null, data=null', () => {
        const fn = vi.fn().mockResolvedValue('ok');
        const { result } = renderHook(() => useApiMutation(fn));

        expect(result.current.loading).toBe(false);
        expect(result.current.error).toBeNull();
        expect(result.current.data).toBeNull();
    });

    it('mutate resolves with the result and exposes data', async () => {
        const fn = vi.fn<(input: { name: string }) => Promise<{ id: string }>>()
            .mockResolvedValue({ id: 'abc' });
        const { result } = renderHook(() => useApiMutation(fn));

        let returned: { id: string } | null = null;
        await act(async () => {
            returned = await result.current.mutate({ name: 'thing' });
        });

        expect(fn).toHaveBeenCalledWith({ name: 'thing' });
        expect(returned).toEqual({ id: 'abc' });
        expect(result.current.data).toEqual({ id: 'abc' });
        expect(result.current.error).toBeNull();
        expect(result.current.loading).toBe(false);
    });

    it('mutate returns null and exposes error on failure', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('boom'));
        const { result } = renderHook(() => useApiMutation(fn));

        let returned: unknown = 'unset';
        await act(async () => {
            returned = await result.current.mutate({});
        });

        expect(returned).toBeNull();
        expect(result.current.error).toBe('boom');
        expect(result.current.data).toBeNull();
        expect(result.current.loading).toBe(false);
    });

    it('successMessage as string fires success toast', async () => {
        const fn = vi.fn().mockResolvedValue('done');
        const { result } = renderHook(() =>
            useApiMutation(fn, { successMessage: 'Saved!' })
        );

        await act(async () => {
            await result.current.mutate('input');
        });

        expect(toast.success).toHaveBeenCalledWith('Saved!');
    });

    it('successMessage as function receives result and input', async () => {
        const fn = vi.fn<(input: string) => Promise<{ id: string }>>()
            .mockResolvedValue({ id: 'abc' });
        const { result } = renderHook(() =>
            useApiMutation(fn, {
                successMessage: (data, input) => `Saved ${input} -> ${data.id}`,
            })
        );

        await act(async () => {
            await result.current.mutate('thing');
        });

        expect(toast.success).toHaveBeenCalledWith('Saved thing -> abc');
    });

    it('errorMessage as string fires error toast', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('actual'));
        const { result } = renderHook(() =>
            useApiMutation(fn, { errorMessage: 'Save failed' })
        );

        await act(async () => {
            await result.current.mutate({});
        });

        expect(toast.error).toHaveBeenCalledWith('Save failed');
    });

    it('errorMessage as function receives the error', async () => {
        const err = new Error('underlying');
        const fn = vi.fn().mockRejectedValue(err);
        const { result } = renderHook(() =>
            useApiMutation(fn, {
                errorMessage: (e) => `Bang: ${(e as Error).message}`,
            })
        );

        await act(async () => {
            await result.current.mutate({});
        });

        expect(toast.error).toHaveBeenCalledWith('Bang: underlying');
    });

    it('falls back to extractErrorMessage when no errorMessage option given', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('raw'));
        const { result } = renderHook(() => useApiMutation(fn));

        await act(async () => {
            await result.current.mutate({});
        });

        expect(toast.error).toHaveBeenCalledWith('raw');
    });

    it('errorToastDuration is passed through to the error toast', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('slow down'));
        const { result } = renderHook(() =>
            useApiMutation(fn, { errorToastDuration: 8000 })
        );

        await act(async () => {
            await result.current.mutate({});
        });

        expect(toast.error).toHaveBeenCalledWith('slow down', { duration: 8000 });
    });

    it('error toast receives no options when errorToastDuration is omitted', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('plain'));
        const { result } = renderHook(() => useApiMutation(fn));

        await act(async () => {
            await result.current.mutate({});
        });

        expect(toast.error).toHaveBeenCalledWith('plain');
    });

    it('onSuccess callback fires with result and input', async () => {
        const onSuccess = vi.fn();
        const fn = vi.fn().mockResolvedValue('result-value');
        const { result } = renderHook(() => useApiMutation(fn, { onSuccess }));

        await act(async () => {
            await result.current.mutate('input-value');
        });

        expect(onSuccess).toHaveBeenCalledWith('result-value', 'input-value');
    });

    it('onError callback fires with the error', async () => {
        const onError = vi.fn();
        const err = new Error('failure');
        const fn = vi.fn().mockRejectedValue(err);
        const { result } = renderHook(() => useApiMutation(fn, { onError }));

        await act(async () => {
            await result.current.mutate({});
        });

        expect(onError).toHaveBeenCalledWith(err);
    });

    it('exposes the in-flight input via pendingInput while mutating', async () => {
        let resolveFn: (v: string) => void = () => {};
        const fn = vi.fn(
            (input: string) => new Promise<string>((resolve) => {
                resolveFn = resolve;
                void input;
            }),
        );
        const { result } = renderHook(() => useApiMutation(fn));

        expect(result.current.pendingInput).toBeNull();

        let promise: Promise<string | null> = Promise.resolve(null);
        act(() => {
            promise = result.current.mutate('scenario-1');
        });

        expect(result.current.pendingInput).toBe('scenario-1');
        expect(result.current.loading).toBe(true);

        await act(async () => {
            resolveFn('done');
            await promise;
        });

        expect(result.current.pendingInput).toBeNull();
    });

    it('clears pendingInput after a failed mutation', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('boom'));
        const { result } = renderHook(() => useApiMutation(fn));

        await act(async () => {
            await result.current.mutate('scenario-2');
        });

        expect(result.current.pendingInput).toBeNull();
    });

    it('reset clears data and error', async () => {
        const fn = vi.fn().mockResolvedValue('hello');
        const { result } = renderHook(() => useApiMutation(fn));

        await act(async () => {
            await result.current.mutate({});
        });

        expect(result.current.data).toBe('hello');

        act(() => {
            result.current.reset();
        });

        expect(result.current.data).toBeNull();
        expect(result.current.error).toBeNull();
    });
});
