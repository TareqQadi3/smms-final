import { api } from '@/lib/api';
import { idempotencyHeaders } from '@/modules/inventory/idempotency';
import type { Page } from '@/modules/inventory/types';
import type { PoInput, PoReceipt, PoSummary, PurchaseOrder, Supplier, SupplierInput } from './types';

type Params = Record<string, string | number | boolean | undefined | null>;
const clean = (p?: Params) =>
  Object.fromEntries(Object.entries(p ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''));

const base = '/procurement/purchase-orders';

export const procurementApi = {
  listSuppliers: (p?: Params) => api.get<Page<Supplier>>('/procurement/suppliers', { params: clean(p) }).then((r) => r.data),
  createSupplier: (b: SupplierInput) => api.post<Supplier>('/procurement/suppliers', b).then((r) => r.data),
  updateSupplier: (id: string, b: SupplierInput) => api.put<Supplier>(`/procurement/suppliers/${id}`, b).then((r) => r.data),
  deactivateSupplier: (id: string) => api.post<Supplier>(`/procurement/suppliers/${id}/deactivate`).then((r) => r.data),

  listPos: (p?: Params) => api.get<Page<PoSummary>>(base, { params: clean(p) }).then((r) => r.data),
  getPo: (id: string) => api.get<PurchaseOrder>(`${base}/${id}`).then((r) => r.data),
  createPo: (b: PoInput) => api.post<PurchaseOrder>(base, b).then((r) => r.data),
  updatePo: (id: string, b: PoInput) => api.put<PurchaseOrder>(`${base}/${id}`, b).then((r) => r.data),
  submitPo: (id: string, version?: number) => api.post<PurchaseOrder>(`${base}/${id}/submit`, { version }).then((r) => r.data),
  approvePo: (id: string, note: string | undefined, version?: number) =>
    api.post<PurchaseOrder>(`${base}/${id}/approve`, { note, version }).then((r) => r.data),
  rejectPo: (id: string, reason: string, version?: number) =>
    api.post<PurchaseOrder>(`${base}/${id}/reject`, { reason, version }).then((r) => r.data),
  cancelPo: (id: string, reason: string, version?: number) =>
    api.post<PurchaseOrder>(`${base}/${id}/cancel`, { reason, version }).then((r) => r.data),
  closePo: (id: string, reason: string, version?: number) =>
    api.post<PurchaseOrder>(`${base}/${id}/close`, { reason, version }).then((r) => r.data),
  receivePo: (id: string, b: { note?: string; lines: { poLineId: string; quantity: number }[] }, key: string) =>
    api.post<PoReceipt>(`${base}/${id}/receive`, b, idempotencyHeaders(key)).then((r) => r.data),
};
