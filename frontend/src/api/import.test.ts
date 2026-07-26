import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    post: vi.mocked(client.post),
};

import { importCsv, importOfx, importPositions, listImportJobs } from './import';
import type { ImportJob } from '../types/import';

const JOB: ImportJob = {
    id: 'j1',
    source: 'csv',
    status: 'completed',
    total_rows: 10,
    successful_rows: 10,
    failed_rows: 0,
    error_message: null,
    created_at: '2026-01-01T00:00:00Z',
};

function sampleFile(name: string): File {
    return new File(['col1,col2\n1,2\n'], name, { type: 'text/csv' });
}

describe('api/import', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('importCsv posts multipart form data with the accountId param', async () => {
        mocks.post.mockResolvedValue({ data: JOB });

        const result = await importCsv('a1', sampleFile('txns.csv'));

        expect(result).toEqual(JOB);
        expect(mocks.post).toHaveBeenCalledTimes(1);
        const [url, body, config] = mocks.post.mock.calls[0];
        expect(url).toBe('/import/csv');
        expect(body).toBeInstanceOf(FormData);
        expect((body as FormData).get('file')).toBeInstanceOf(File);
        // Non-null assertions: mocks.post's config param is now typed from the real
        // AxiosInstance signature (optional 3rd arg) via vi.mocked(client.post); at
        // runtime it's always present because importCsv always passes one.
        expect(config!.params).toEqual({ accountId: 'a1' });
        expect(config!.headers!['Content-Type']).toBe('multipart/form-data');
    });

    it('importCsv includes the format param when supplied', async () => {
        mocks.post.mockResolvedValue({ data: JOB });

        await importCsv('a1', sampleFile('txns.csv'), 'fidelity');

        const config = mocks.post.mock.calls[0][2];
        expect(config!.params).toEqual({ accountId: 'a1', format: 'fidelity' });
    });

    it('importOfx posts to the ofx endpoint', async () => {
        mocks.post.mockResolvedValue({ data: { ...JOB, source: 'ofx' } });

        const result = await importOfx('a1', sampleFile('txns.ofx'));

        expect(result.source).toBe('ofx');
        const [url, , config] = mocks.post.mock.calls[0];
        expect(url).toBe('/import/ofx');
        expect(config!.params).toEqual({ accountId: 'a1' });
    });

    it('importPositions posts to the positions endpoint with format', async () => {
        mocks.post.mockResolvedValue({ data: JOB });

        await importPositions('a1', sampleFile('pos.csv'), 'schwab');

        const [url, , config] = mocks.post.mock.calls[0];
        expect(url).toBe('/import/positions');
        expect(config!.params).toEqual({ accountId: 'a1', format: 'schwab' });
    });

    it('listImportJobs returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [JOB] });

        const result = await listImportJobs();

        expect(result).toEqual([JOB]);
        expect(mocks.get).toHaveBeenCalledWith('/import/jobs');
    });

    it('propagates server errors', async () => {
        mocks.post.mockRejectedValue(new Error('415'));

        await expect(importCsv('a1', sampleFile('bad.csv'))).rejects.toThrow('415');
    });
});
