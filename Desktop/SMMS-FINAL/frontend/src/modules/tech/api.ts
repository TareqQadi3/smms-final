import { api } from '@/lib/api';
import type { PageResponse } from '@/lib/types';
import type { ActionSpec } from '@/lib/offline/queue';
import type {
  AssetWorkOrderRow, Attachment, HistoryEntry, MaterialRequest, MaterialRequestSummary,
  NotificationItem, PhotoPhase, TechTransition, TechWorkOrder,
} from './types';

/** Read endpoints. The server scopes everything: a technician only ever sees tickets assigned to them. */
export const techApi = {
  list: (params: Record<string, string | number | undefined>) =>
    api.get<PageResponse<TechWorkOrder>>('/work-orders', { params }),
  get: (id: string) => api.get<TechWorkOrder>(`/work-orders/${id}`),
  history: (id: string) => api.get<HistoryEntry[]>(`/work-orders/${id}/history`),
  attachments: (id: string) => api.get<Attachment[]>(`/work-orders/${id}/attachments`),
  /** Blob download with the Bearer header (the token never goes into a URL). */
  attachmentBlob: (id: string, attachmentId: string) =>
    api.get<Blob>(`/work-orders/${id}/attachments/${attachmentId}/content`, { responseType: 'blob' }),
  assetWorkOrders: (assetId: string, size = 5) =>
    api.get<PageResponse<AssetWorkOrderRow>>(`/assets/${assetId}/work-orders`, { params: { page: 0, size } }),
  materialRequests: (workOrderId: string) =>
    api.get<MaterialRequestSummary[]>(`/work-orders/${workOrderId}/material-requests`),
  materialRequest: (id: string) => api.get<MaterialRequest>(`/material-requests/${id}`),
  notifications: (page = 0, size = 20) =>
    api.get<PageResponse<NotificationItem>>('/notifications', { params: { page, size, sort: 'createdAt,desc' } }),
};

type Ref = { id: string; ticketNumber: string };

/**
 * Write actions are described, not sent: the offline queue persists and sends them with an
 * Idempotency-Key that stays the same for every retry.
 */
export const techActions = {
  /** assigned -> in_progress (start), in_progress -> waiting_materials | completed (note = tech report). */
  transition(wo: Ref, target: TechTransition, note?: string): ActionSpec {
    const text = note?.trim();
    return {
      kind: 'transition',
      workOrderId: wo.id,
      url: `/work-orders/${wo.id}/transition`,
      json: { targetStatus: target, ...(text ? { note: text } : {}) },
      dedupeKey: `transition:${wo.id}:${target}`,
      reconcile: { workOrderId: wo.id, status: target },
      meta: { ticketNumber: wo.ticketNumber, target },
    };
  },

  note(wo: Ref, text: string): ActionSpec {
    const clean = text.trim();
    return {
      kind: 'note',
      workOrderId: wo.id,
      url: `/work-orders/${wo.id}/notes`,
      json: { text: clean },
      dedupeKey: `note:${wo.id}:${clean}`,
      meta: { ticketNumber: wo.ticketNumber, excerpt: clean.slice(0, 80) },
    };
  },

  consume(wo: Ref, mr: { id: string; requestNumber: string }, lines: { lineId: string; quantity: number }[], note?: string): ActionSpec {
    const text = note?.trim();
    const body = { lines, ...(text ? { note: text } : {}) };
    return {
      kind: 'consume',
      workOrderId: wo.id,
      url: `/material-requests/${mr.id}/consume`,
      json: body,
      dedupeKey: `consume:${mr.id}:${JSON.stringify(body)}`,
      meta: { ticketNumber: wo.ticketNumber, requestNumber: mr.requestNumber },
    };
  },

  photo(wo: Ref, file: File, phase: PhotoPhase): ActionSpec {
    return {
      kind: 'photo',
      workOrderId: wo.id,
      url: `/work-orders/${wo.id}/attachments`,
      params: { phase },
      file: { blob: file, name: file.name, type: file.type },
      meta: { ticketNumber: wo.ticketNumber, phase, fileName: file.name },
    };
  },
};
