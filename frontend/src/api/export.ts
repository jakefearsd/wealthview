import client from './client';

function downloadBlob(data: string | object, filename: string, type: string) {
    const content = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

export async function downloadJson() {
    const { data } = await client.get('/export/json');
    downloadBlob(data, 'wealthview-export.json', 'application/json');
}

export async function downloadCsv(entity: 'accounts' | 'transactions' | 'holdings' | 'properties') {
    const { data } = await client.get(`/export/csv/${entity}`, {
        responseType: 'text',
        headers: { Accept: 'text/csv' },
    });
    downloadBlob(data, `${entity}.csv`, 'text/csv');
}
