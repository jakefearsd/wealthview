export interface PageResponse<T> {
    data: T[];
    page: number;
    size: number;
    total: number;
}

export interface ErrorResponse {
    error: string;
    message: string;
    status: number;
}
