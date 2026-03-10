import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useCrudForm } from './useCrudForm';
import toast from 'react-hot-toast';

vi.mock('react-hot-toast', () => ({
    default: {
        success: vi.fn(),
        error: vi.fn(),
    },
}));

vi.mock('../utils/errorMessage', () => ({
    extractErrorMessage: vi.fn((err: unknown) => (err as Error).message ?? 'Unknown error'),
}));

interface TestFormData {
    name: string;
    value: number;
}

const initialFormData: TestFormData = { name: '', value: 0 };

function createOptions(overrides = {}) {
    return {
        createFn: vi.fn().mockResolvedValue({ id: 'new-1' }),
        updateFn: vi.fn().mockResolvedValue({ id: 'existing-1' }),
        deleteFn: vi.fn().mockResolvedValue(undefined),
        entityName: 'Widget',
        initialFormData,
        onSuccess: vi.fn(),
        ...overrides,
    };
}

describe('useCrudForm', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('initializes with null editingId and initial form data', () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        expect(result.current.editingId).toBeNull();
        expect(result.current.formData).toEqual(initialFormData);
        expect(result.current.isSubmitting).toBe(false);
    });

    it('handleSave calls createFn when editingId is null', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        act(() => {
            result.current.setFormData({ name: 'Test', value: 42 });
        });

        await act(async () => {
            await result.current.handleSave();
        });

        expect(options.createFn).toHaveBeenCalledWith({ name: 'Test', value: 42 });
        expect(options.updateFn).not.toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledWith('Widget created');
        expect(options.onSuccess).toHaveBeenCalled();
    });

    it('handleSave calls updateFn when editingId is set', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        act(() => {
            result.current.startEdit('existing-1', { name: 'Existing', value: 100 });
        });

        await act(async () => {
            await result.current.handleSave();
        });

        expect(options.updateFn).toHaveBeenCalledWith('existing-1', { name: 'Existing', value: 100 });
        expect(options.createFn).not.toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledWith('Widget updated');
        expect(options.onSuccess).toHaveBeenCalled();
    });

    it('handleSave resets form after successful save', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        act(() => {
            result.current.startEdit('id-1', { name: 'Editing', value: 5 });
        });

        await act(async () => {
            await result.current.handleSave();
        });

        expect(result.current.editingId).toBeNull();
        expect(result.current.formData).toEqual(initialFormData);
    });

    it('handleSave shows error toast on failure', async () => {
        const options = createOptions({
            createFn: vi.fn().mockRejectedValue(new Error('Network failure')),
        });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleSave();
        });

        expect(toast.error).toHaveBeenCalledWith('Network failure');
        expect(options.onSuccess).not.toHaveBeenCalled();
    });

    it('handleSave does not save when validate returns an error', async () => {
        const options = createOptions({
            validate: () => 'Name is required',
        });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleSave();
        });

        expect(toast.error).toHaveBeenCalledWith('Name is required');
        expect(options.createFn).not.toHaveBeenCalled();
        expect(options.updateFn).not.toHaveBeenCalled();
        expect(result.current.isSubmitting).toBe(false);
    });

    it('handleSave proceeds when validate returns undefined', async () => {
        const options = createOptions({
            validate: () => undefined,
        });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleSave();
        });

        expect(options.createFn).toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledWith('Widget created');
    });

    it('handleDelete calls deleteFn and shows success toast', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleDelete('delete-1');
        });

        expect(options.deleteFn).toHaveBeenCalledWith('delete-1');
        expect(toast.success).toHaveBeenCalledWith('Widget deleted');
        expect(options.onSuccess).toHaveBeenCalled();
    });

    it('handleDelete shows error toast on failure', async () => {
        const options = createOptions({
            deleteFn: vi.fn().mockRejectedValue(new Error('Delete failed')),
        });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleDelete('id-1');
        });

        expect(toast.error).toHaveBeenCalledWith('Delete failed');
        expect(options.onSuccess).not.toHaveBeenCalled();
    });

    it('handleDelete does nothing when deleteFn is not provided', async () => {
        const options = createOptions({ deleteFn: undefined });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleDelete('id-1');
        });

        expect(toast.success).not.toHaveBeenCalled();
        expect(toast.error).not.toHaveBeenCalled();
    });

    it('resetForm clears editingId and resets formData', () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        act(() => {
            result.current.startEdit('id-1', { name: 'Editing', value: 99 });
        });

        expect(result.current.editingId).toBe('id-1');
        expect(result.current.formData).toEqual({ name: 'Editing', value: 99 });

        act(() => {
            result.current.resetForm();
        });

        expect(result.current.editingId).toBeNull();
        expect(result.current.formData).toEqual(initialFormData);
    });

    it('startEdit sets editingId and formData', () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        act(() => {
            result.current.startEdit('id-42', { name: 'Item', value: 42 });
        });

        expect(result.current.editingId).toBe('id-42');
        expect(result.current.formData).toEqual({ name: 'Item', value: 42 });
    });

    it('formatError is used when provided', async () => {
        const options = createOptions({
            createFn: vi.fn().mockRejectedValue(new Error('boom')),
            formatError: (_err: unknown, action: string) => `Failed to ${action} widget`,
        });
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleSave();
        });

        expect(toast.error).toHaveBeenCalledWith('Failed to create widget');
    });

    it('onSuccess is called after successful save', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleSave();
        });

        expect(options.onSuccess).toHaveBeenCalledTimes(1);
    });

    it('onSuccess is called after successful delete', async () => {
        const options = createOptions();
        const { result } = renderHook(() => useCrudForm<unknown, TestFormData>(options));

        await act(async () => {
            await result.current.handleDelete('id-1');
        });

        expect(options.onSuccess).toHaveBeenCalledTimes(1);
    });
});
