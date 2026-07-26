// The dashboard summary wire-format trio is owned by @wealthview/shared (used
// by both web and mobile); this module re-exports it under the existing local
// names to keep existing import paths and identifiers stable. The portfolio
// history / projection snapshot types below have no mobile consumer yet and
// stay defined here.
export type {
    DashboardSummaryResponse as DashboardSummary,
    DashboardAccountSummary as AccountSummary,
    DashboardAllocationEntry as AllocationEntry,
} from '@wealthview/shared';

export interface CombinedPortfolioDataPoint {
    date: string;
    total_value: number;
    investment_value: number;
    property_equity: number;
}

export interface CombinedPortfolioHistory {
    data_points: CombinedPortfolioDataPoint[];
    weeks: number;
    investment_account_count: number;
    property_count: number;
}

export interface SnapshotProjectionDataPoint {
    year: number;
    date: string;
    total_value: number;
    investment_value: number;
    property_equity: number;
}

export interface SnapshotProjection {
    data_points: SnapshotProjectionDataPoint[];
    projection_years: number;
    investment_account_count: number;
    property_count: number;
    portfolio_cagr: number;
}
