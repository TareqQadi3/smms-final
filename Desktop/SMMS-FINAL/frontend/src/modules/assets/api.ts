import { api } from '@/lib/api';
import { clean, type PageData } from './lib';
import type {
  Asset, AssetFormValues, AssetListParams, AssetWorkOrderRow, Downtime, MaterialRow,
} from './types';

/** Empty strings become null so optional columns are cleared, not stored as ''. */
function body(v: AssetFormValues): Record<string, unknown> {
  const n = (s: string) => (s.trim() === '' ? null : s.trim());
  return {
    departmentId: v.departmentId || null,
    name: v.name.trim(),
    nameAr: v.nameAr.trim(),
    assetCode: n(v.assetCode),
    category: n(v.category),
    manufacturer: n(v.manufacturer),
    model: n(v.model),
    serialNumber: n(v.serialNumber),
    location: n(v.location),
    installDate: v.installDate || null,
    warrantyExpiry: v.warrantyExpiry || null,
    criticality: v.criticality || null,
    status: v.status,
  };
}

export const assetsApi = {
  list: (p: AssetListParams) => api.get<PageData<Asset>>('/assets', { params: clean({ ...p }) }).then((r) => r.data),
  get: (id: string) => api.get<Asset>(`/assets/${id}`).then((r) => r.data),
  create: (v: AssetFormValues) =>
    api.post<Asset>('/assets', { centerId: v.centerId, ...body(v) }).then((r) => r.data),
  update: (id: string, v: AssetFormValues, version: number) =>
    api.put<Asset>(`/assets/${id}`, { version, ...body(v) }).then((r) => r.data),
  decommission: (id: string, reason: string) =>
    api.post<Asset>(`/assets/${id}/decommission`, { reason }).then((r) => r.data),
  workOrders: (id: string, page = 0, size = 10) =>
    api.get<PageData<AssetWorkOrderRow>>(`/assets/${id}/work-orders`, { params: { page, size } }).then((r) => r.data),
  materials: (id: string, from?: string, to?: string) =>
    api.get<MaterialRow[]>(`/assets/${id}/materials`, { params: clean({ from, to }) }).then((r) => r.data),
  downtime: (id: string, page = 0, size = 10) =>
    api.get<PageData<Downtime>>(`/assets/${id}/downtime`, { params: { page, size } }).then((r) => r.data),
  startDowntime: (id: string, data: { reason: string; workOrderId?: string; startedAt?: string }) =>
    api.post<Downtime>(`/assets/${id}/downtime`, data).then((r) => r.data),
  endDowntime: (id: string, dtId: string) =>
    api.post<Downtime>(`/assets/${id}/downtime/${dtId}/end`, {}).then((r) => r.data),
};
