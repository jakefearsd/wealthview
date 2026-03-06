import type { AxiosError } from 'axios';

interface ApiErrorBody {
    message?: string;
    error?: string;
}

/**
 * Extracts a human-readable error message from an Axios error or generic exception.
 * Prioritises the server's error body, falls back to the HTTP status text,
 * then the JS exception message.
 */
export function extractErrorMessage(err: unknown): string {
    if (err && typeof err === 'object' && 'isAxiosError' in err) {
        const axiosErr = err as AxiosError<ApiErrorBody>;
        const body = axiosErr.response?.data;
        if (body?.message) return body.message;
        if (axiosErr.response) return `Server error: ${axiosErr.response.status} ${axiosErr.response.statusText}`;
        if (axiosErr.request) return 'No response from server — is the backend running?';
        return axiosErr.message;
    }
    if (err instanceof Error) return err.message;
    return 'An unexpected error occurred';
}
