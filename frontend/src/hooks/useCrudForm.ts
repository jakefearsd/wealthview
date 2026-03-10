import { useState, useCallback } from 'react';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';

interface UseCrudFormOptions<T, FormData> {
    createFn: (data: FormData) => Promise<T>;
    updateFn: (id: string, data: FormData) => Promise<T>;
    deleteFn?: (id: string) => Promise<void>;
    entityName: string;
    initialFormData: FormData;
    onSuccess: () => void;
    validate?: (data: FormData) => string | undefined;
    formatError?: (err: unknown, action: 'create' | 'update' | 'delete') => string;
}

interface UseCrudFormReturn<FormData> {
    editingId: string | null;
    formData: FormData;
    setFormData: React.Dispatch<React.SetStateAction<FormData>>;
    isSubmitting: boolean;
    startEdit: (id: string, data: FormData) => void;
    resetForm: () => void;
    handleSave: () => Promise<void>;
    handleDelete: (id: string) => Promise<void>;
}

export function useCrudForm<T, FormData>(options: UseCrudFormOptions<T, FormData>): UseCrudFormReturn<FormData> {
    const { createFn, updateFn, deleteFn, entityName, initialFormData, onSuccess, validate, formatError } = options;

    const [editingId, setEditingId] = useState<string | null>(null);
    const [formData, setFormData] = useState<FormData>(initialFormData);
    const [isSubmitting, setIsSubmitting] = useState(false);

    const resetForm = useCallback(() => {
        setEditingId(null);
        setFormData(initialFormData);
    }, [initialFormData]);

    const startEdit = useCallback((id: string, data: FormData) => {
        setEditingId(id);
        setFormData(data);
    }, []);

    const handleSave = useCallback(async () => {
        if (validate) {
            const error = validate(formData);
            if (error) {
                toast.error(error);
                return;
            }
        }

        setIsSubmitting(true);
        try {
            if (editingId) {
                await updateFn(editingId, formData);
                toast.success(`${entityName} updated`);
            } else {
                await createFn(formData);
                toast.success(`${entityName} created`);
            }
            resetForm();
            onSuccess();
        } catch (err: unknown) {
            const action = editingId ? 'update' as const : 'create' as const;
            toast.error(formatError ? formatError(err, action) : extractErrorMessage(err));
        } finally {
            setIsSubmitting(false);
        }
    }, [editingId, formData, createFn, updateFn, entityName, onSuccess, resetForm, validate, formatError]);

    const handleDelete = useCallback(async (id: string) => {
        if (!deleteFn) return;
        try {
            await deleteFn(id);
            toast.success(`${entityName} deleted`);
            onSuccess();
        } catch (err: unknown) {
            toast.error(formatError ? formatError(err, 'delete') : extractErrorMessage(err));
        }
    }, [deleteFn, entityName, onSuccess, formatError]);

    return {
        editingId,
        formData,
        setFormData,
        isSubmitting,
        startEdit,
        resetForm,
        handleSave,
        handleDelete,
    };
}
