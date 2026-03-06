import client from './client';
import type { ImportJob } from '../types/import';

export async function importCsv(accountId: string, file: File, format?: string): Promise<ImportJob> {
    const formData = new FormData();
    formData.append('file', file);
    const params: Record<string, string> = { accountId };
    if (format) params.format = format;
    const { data } = await client.post<ImportJob>('/import/csv', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        params,
    });
    return data;
}

export async function importOfx(accountId: string, file: File): Promise<ImportJob> {
    const formData = new FormData();
    formData.append('file', file);
    const { data } = await client.post<ImportJob>('/import/ofx', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        params: { accountId },
    });
    return data;
}

export async function listImportJobs(): Promise<ImportJob[]> {
    const { data } = await client.get<ImportJob[]>('/import/jobs');
    return data;
}
