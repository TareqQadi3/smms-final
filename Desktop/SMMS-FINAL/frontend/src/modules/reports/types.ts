export interface ReportFilters {
  from: string;
  to: string;
  centerId: string;
  regionId: string;
}

export interface Group { key: string; label: string; count: number }
export interface Volume { from: string; to: string; groupBy: string; groups: Group[]; total: number }

export interface TurnaroundRow {
  key: string;
  completedCount: number;
  avgHours: number | null;
  p50Hours: number | null;
  p90Hours: number | null;
}

export interface SlaRow {
  priority: string;
  due: number;
  metOnTime: number;
  breached: number;
  compliancePct: number | null;
  avgPausedHours: number | null;
  openBreached: number;
}

export interface EscalationReport {
  from: string;
  to: string;
  /** false: the escalation data does not exist yet on this deployment (not an error). */
  available: boolean;
  groups: { level: number; trigger: string | null; count: number }[];
  byDay: { day: string; count: number }[];
}

export interface TechnicianRow {
  technician: { id: string; fullName: string | null };
  assigned: number;
  completed: number;
  open: number;
  reopened: number;
  avgTurnaroundHours: number | null;
  completedByPriority: { critical: number; high: number; medium: number; low: number };
  emergencyHandled: number;
}

export interface ReportPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Count { key: string; count: number }
export interface AssetSummary {
  from: string;
  to: string;
  totalAssets: number;
  decommissioned: number;
  byStatus: Count[];
  byCategory: Count[];
  byCriticality: Count[];
  downtimeHours: number;
  downtimeByAsset: { assetId: string; assetCode: string | null; assetName: string; periods: number; hours: number }[];
}

export interface PmCompliance {
  from: string;
  to: string;
  due: number;
  generated: number;
  failed: number;
  skipped: number;
  completedOnTime: number;
  completedLate: number;
  overdue: number;
  open: number;
  dueNotGenerated: number;
  rows: {
    planId: string;
    planTitle: string;
    assetId: string;
    assetCode: string | null;
    assetName: string;
    dueDate: string | number;
    runStatus: string;
    ticketNumber: string | null;
    workOrderStatus: string | null;
    completedAt: string | null;
    outcome: string;
  }[];
}

/** Inventory and purchasing reports share one tabular shape. */
export interface TabularReport {
  name: string;
  from: string | null;
  to: string | null;
  columns: string[];
  rows: (string | number | boolean | null)[][];
}

export type InventoryReportId = 'stock' | 'movements' | 'consumption' | 'open-material-requests' | 'purchasing';
