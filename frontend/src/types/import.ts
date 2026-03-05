export interface ImportJob {
    id: string;
    source: string;
    status: string;
    total_rows: number;
    successful_rows: number;
    failed_rows: number;
    error_message: string | null;
    created_at: string;
}
