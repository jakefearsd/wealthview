import { useState, useCallback } from 'react';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';

interface UseApiMutationOptions<TInput, TOutput> {
    successMessage?: string | ((data: TOutput, input: TInput) => string);
    errorMessage?: string | ((err: unknown) => string);
    onSuccess?: (data: TOutput, input: TInput) => void;
    onError?: (err: unknown) => void;
}

interface UseApiMutationResult<TInput, TOutput> {
    mutate: (input: TInput) => Promise<TOutput | null>;
    loading: boolean;
    error: string | null;
    data: TOutput | null;
    reset: () => void;
}

export function useApiMutation<TInput, TOutput>(
    mutationFn: (input: TInput) => Promise<TOutput>,
    options: UseApiMutationOptions<TInput, TOutput> = {}
): UseApiMutationResult<TInput, TOutput> {
    const { successMessage, errorMessage, onSuccess, onError } = options;

    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [data, setData] = useState<TOutput | null>(null);

    const mutate = useCallback(async (input: TInput): Promise<TOutput | null> => {
        setLoading(true);
        setError(null);
        try {
            const result = await mutationFn(input);
            setData(result);
            if (successMessage !== undefined) {
                const msg = typeof successMessage === 'function'
                    ? successMessage(result, input)
                    : successMessage;
                toast.success(msg);
            }
            onSuccess?.(result, input);
            return result;
        } catch (err: unknown) {
            const msg = errorMessage !== undefined
                ? (typeof errorMessage === 'function' ? errorMessage(err) : errorMessage)
                : extractErrorMessage(err);
            setError(msg);
            toast.error(msg);
            onError?.(err);
            return null;
        } finally {
            setLoading(false);
        }
    }, [mutationFn, successMessage, errorMessage, onSuccess, onError]);

    const reset = useCallback(() => {
        setError(null);
        setData(null);
    }, []);

    return { mutate, loading, error, data, reset };
}
