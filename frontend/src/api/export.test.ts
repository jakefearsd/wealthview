import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
    },
}));

import { downloadBlob, downloadJson, downloadCsv } from './export';

describe('api/export', () => {
    let createObjectURL: ReturnType<typeof vi.fn>;
    let revokeObjectURL: ReturnType<typeof vi.fn>;
    let clickSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        mocks.get.mockReset();
        createObjectURL = vi.fn(() => 'blob:mock-url');
        revokeObjectURL = vi.fn();
        URL.createObjectURL = createObjectURL;
        URL.revokeObjectURL = revokeObjectURL;
        clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    });

    afterEach(() => {
        clickSpy.mockRestore();
    });

    it('downloadBlob serializes an object to pretty JSON and triggers a download', () => {
        downloadBlob({ a: 1 }, 'data.json', 'application/json');

        expect(createObjectURL).toHaveBeenCalledTimes(1);
        const blob = createObjectURL.mock.calls[0][0] as Blob;
        expect(blob.type).toBe('application/json');
        expect(clickSpy).toHaveBeenCalledTimes(1);
        expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
    });

    it('downloadBlob passes string content through unchanged', () => {
        downloadBlob('a,b\n1,2', 'data.csv', 'text/csv');

        const blob = createObjectURL.mock.calls[0][0] as Blob;
        expect(blob.type).toBe('text/csv');
        // 'a,b\n1,2' is 7 bytes — confirms the string was not re-serialized as JSON.
        expect(blob.size).toBe(7);
    });

    it('downloadJson fetches /export/json and triggers a download', async () => {
        mocks.get.mockResolvedValue({ data: { accounts: [] } });

        await downloadJson();

        expect(mocks.get).toHaveBeenCalledWith('/export/json');
        expect(clickSpy).toHaveBeenCalledTimes(1);
    });

    it('downloadCsv requests the entity-scoped CSV with text response type', async () => {
        mocks.get.mockResolvedValue({ data: 'id,name\n1,Foo' });

        await downloadCsv('accounts');

        expect(mocks.get).toHaveBeenCalledWith('/export/csv/accounts', {
            responseType: 'text',
            headers: { Accept: 'text/csv' },
        });
        expect(clickSpy).toHaveBeenCalledTimes(1);
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(downloadJson()).rejects.toThrow('500');
    });
});
