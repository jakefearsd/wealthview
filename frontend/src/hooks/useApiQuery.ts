import { useState, useEffect, useCallback } from 'react';

interface UseApiQueryResult<T> {
    data: T | null;
    loading: boolean;
    error: string | null;
    refetch: () => void;
}

export function useApiQuery<T>(fetchFn: () => Promise<T>, deps: unknown[] = []): UseApiQueryResult<T> {
    const [data, setData] = useState<T | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [trigger, setTrigger] = useState(0);

    const refetch = useCallback(() => setTrigger((t) => t + 1), []);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        fetchFn()
            .then((result) => {
                if (!cancelled) {
                    setData(result);
                    setLoading(false);
                }
            })
            .catch((err) => {
                if (!cancelled) {
                    setError(err?.response?.data?.message || err.message || 'An error occurred');
                    setLoading(false);
                }
            });
        return () => {
            cancelled = true;
        };
        // fetchFn is intentionally omitted: callers typically pass an inline closure
        // that would change every render. Pass the closure's inputs via `deps` to
        // re-run when they change; refetch() is the manual way to re-run.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [trigger, ...deps]);

    return { data, loading, error, refetch };
}
