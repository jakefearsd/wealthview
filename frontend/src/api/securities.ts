import client from './client';

export interface SecurityClassificationResponse {
    symbol: string;
    asset_class: string;
}

export async function setClassification(symbol: string, assetClass: string): Promise<SecurityClassificationResponse> {
    const { data } = await client.put<SecurityClassificationResponse>(
        `/securities/${encodeURIComponent(symbol)}/classification`,
        { asset_class: assetClass },
    );
    return data;
}
