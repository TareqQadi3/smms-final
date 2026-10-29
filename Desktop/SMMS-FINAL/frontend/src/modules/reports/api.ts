import { api, regionApi } from '@/lib/api';
import { clean } from '@/modules/assets/lib';
import type {
  AssetSummary, EscalationReport, PmCompliance, ReportFilters, ReportPage, SlaRow, TabularReport, TechnicianRow,
  TurnaroundRow, Volume, InventoryReportId,
} from './types';

export const REPORT_PATHS = {
  volume: '/reports/work-orders/volume',
  turnaround: '/reports/work-orders/turnaround',
  sla: '/reports/sla',
  escalations: '/reports/escalations',
  technicians: '/reports/technicians',
  assetSummary: '/reports/assets/summary',
  pmCompliance: '/reports/pm/compliance',
  stock: '/reports/inventory/stock',
  movements: '/reports/inventory/movements',
  consumption: '/reports/inventory/consumption',
  'open-material-requests': '/reports/inventory/open-material-requests',
  purchasing: '/reports/purchasing/orders',
} as const;

/** Work-order reports accept from/to/centerId/regionId. */
export function workOrderParams(f: ReportFilters, extra: Record<string, unknown> = {}) {
  return clean({ from: f.from, to: f.to, centerId: f.centerId, regionId: f.regionId, ...extra });
}
/** Asset, PM, inventory and purchasing reports have no region parameter. */
export function centerParams(f: ReportFilters, extra: Record<string, unknown> = {}) {
  return clean({ from: f.from, to: f.to, centerId: f.centerId, ...extra });
}
/** Stock and open-request reports are snapshots: no date range. */
export function snapshotParams(f: ReportFilters, extra: Record<string, unknown> = {}) {
  return clean({ centerId: f.centerId, ...extra });
}

export const reportsApi = {
  volume: (p: Record<string, unknown>) => api.get<Volume>(REPORT_PATHS.volume, { params: p }).then((r) => r.data),
  turnaround: (p: Record<string, unknown>) => api.get<TurnaroundRow[]>(REPORT_PATHS.turnaround, { params: p }).then((r) => r.data),
  sla: (p: Record<string, unknown>) => api.get<SlaRow[]>(REPORT_PATHS.sla, { params: p }).then((r) => r.data),
  escalations: (p: Record<string, unknown>) => api.get<EscalationReport>(REPORT_PATHS.escalations, { params: p }).then((r) => r.data),
  technicians: (p: Record<string, unknown>) =>
    api.get<ReportPage<TechnicianRow>>(REPORT_PATHS.technicians, { params: p }).then((r) => r.data),
  assetSummary: (p: Record<string, unknown>) => api.get<AssetSummary>(REPORT_PATHS.assetSummary, { params: p }).then((r) => r.data),
  pmCompliance: (p: Record<string, unknown>) => api.get<PmCompliance>(REPORT_PATHS.pmCompliance, { params: p }).then((r) => r.data),
  tabular: (id: InventoryReportId, p: Record<string, unknown>) =>
    api.get<TabularReport>(REPORT_PATHS[id], { params: p }).then((r) => r.data),
  regions: () => regionApi.getAll().then((r) => (r.data ?? []) as { id: string; name: string; nameAr: string }[]),
};
