import { api } from '@/lib/api';
import { idempotencyHeaders } from '@/modules/inventory/idempotency';
import type { Page } from '@/modules/inventory/types';
import type { PurchaseOrder } from '@/modules/procurement/types';
import type { CreateMrInput, LineQty, MaterialRequest, MrSummary } from './types';

type Params = Record<string, string | number | boolean | undefined | null>;
const clean = (p?: Params) =>
  Object.fromEntries(Object.entries(p ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''));

const base = '/material-requests';
const post = (id: string, action: string, body: unknown, key?: string) =>
  api.post<MaterialRequest>(`${base}/${id}/${action}`, body, key ? idempotencyHeaders(key) : undefined).then((r) => r.data);

export const materialsApi = {
  list: (p?: Params) => api.get<Page<MrSummary>>(base, { params: clean(p) }).then((r) => r.data),
  get: (id: string) => api.get<MaterialRequest>(`${base}/${id}`).then((r) => r.data),
  listForWorkOrder: (woId: string) => api.get<MrSummary[]>(`/work-orders/${woId}/material-requests`).then((r) => r.data),
  create: (woId: string, b: CreateMrInput) =>
    api.post<MaterialRequest>(`/work-orders/${woId}/material-requests`, b).then((r) => r.data),
  approve: (id: string, b: { note?: string; lines?: { lineId: string; approvedQuantity: number }[]; version?: number }) =>
    post(id, 'approve', b),
  reject: (id: string, reason: string, version?: number) => post(id, 'reject', { reason, version }),
  reserve: (id: string, version: number | undefined, key: string) => post(id, 'reserve', { version }, key),
  issue: (id: string, lines: LineQty[], version: number | undefined, key: string) => post(id, 'issue', { lines, version }, key),
  consume: (id: string, lines: LineQty[], note: string | undefined, version: number | undefined, key: string) =>
    post(id, 'consume', { lines, note, version }, key),
  giveBack: (id: string, lines: LineQty[], reason: string, version: number | undefined, key: string) =>
    post(id, 'return', { lines, reason, version }, key),
  close: (id: string, version?: number) => post(id, 'close', { version }),
  cancel: (id: string, reason: string, version?: number) => post(id, 'cancel', { reason, version }),
  createPurchaseOrder: (id: string, supplierId: string) =>
    api.post<PurchaseOrder>(`${base}/${id}/purchase-order`, { supplierId }).then((r) => r.data),
};
