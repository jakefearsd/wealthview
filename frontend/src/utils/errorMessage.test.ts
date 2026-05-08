import { describe, it, expect } from 'vitest';
import type { AxiosError, AxiosResponse } from 'axios';
import { extractErrorMessage } from './errorMessage';

interface ApiErrorBody {
    message?: string;
    error?: string;
}

function axiosErrorWith(opts: {
    response?: Partial<AxiosResponse<ApiErrorBody>>;
    request?: object;
    message?: string;
}): AxiosError<ApiErrorBody> {
    const err = {
        isAxiosError: true,
        name: 'AxiosError',
        message: opts.message ?? '',
        response: opts.response as AxiosResponse<ApiErrorBody> | undefined,
        request: opts.request,
        config: undefined,
        toJSON: () => ({}),
    } as unknown as AxiosError<ApiErrorBody>;
    return err;
}

describe('extractErrorMessage', () => {
    it('returns the server-provided message field when present', () => {
        const err = axiosErrorWith({
            response: { data: { message: 'Invalid email' }, status: 400, statusText: 'Bad Request' },
        });

        expect(extractErrorMessage(err)).toBe('Invalid email');
    });

    it('falls back to status + statusText when no message body is present', () => {
        const err = axiosErrorWith({
            response: { data: {}, status: 503, statusText: 'Service Unavailable' },
        });

        expect(extractErrorMessage(err)).toBe('Server error: 503 Service Unavailable');
    });

    it('returns the no-response sentinel when the request was made but no response came back', () => {
        const err = axiosErrorWith({ request: {} });

        expect(extractErrorMessage(err)).toBe('No response from server — is the backend running?');
    });

    it('returns the axios error message when no response and no request are attached', () => {
        const err = axiosErrorWith({ message: 'Network Error' });

        expect(extractErrorMessage(err)).toBe('Network Error');
    });

    it('returns the message of a plain Error instance', () => {
        expect(extractErrorMessage(new Error('boom'))).toBe('boom');
    });

    it('returns the catch-all message for unknown values', () => {
        expect(extractErrorMessage('a string')).toBe('An unexpected error occurred');
        expect(extractErrorMessage(undefined)).toBe('An unexpected error occurred');
        expect(extractErrorMessage(null)).toBe('An unexpected error occurred');
        expect(extractErrorMessage({ random: 'object' })).toBe('An unexpected error occurred');
    });
});
