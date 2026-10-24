import { api } from '@/lib/api';
import type {
  AssetOption, AttachmentMeta, DeliveryPage, Escalation, InfoRequest, NotificationPreferences,
  ProviderStatus, Rating, WorkOrderDetail,
} from './types';

/** Idempotency keys are optional on notes; one key per submit attempt makes a retry safe. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export const workflowApi = {
  infoRequests: (id: string) => api.get<InfoRequest[]>(`/work-orders/${id}/info-requests`),
  escalations: (id: string) => api.get<Escalation[]>(`/work-orders/${id}/escalations`),
  escalate: (id: string, reason: string) => api.post<Escalation>(`/work-orders/${id}/escalate`, { reason }),
  declareEmergency: (id: string, reason: string) =>
    api.post<WorkOrderDetail>(`/work-orders/${id}/emergency`, { reason }),
  clearEmergency: (id: string, reason: string) =>
    api.post<WorkOrderDetail>(`/work-orders/${id}/emergency/clear`, { reason }),
  rating: (id: string) => api.get<Rating>(`/work-orders/${id}/rating`),
  addNote: (id: string, text: string, key?: string) =>
    api.post(`/work-orders/${id}/notes`, { text }, key ? { headers: { 'Idempotency-Key': key } } : undefined),
  attachments: (id: string) => api.get<AttachmentMeta[]>(`/work-orders/${id}/attachments`),
  /** Authorised blob download (Bearer header added by the api client; never a token in a URL). */
  attachmentBlob: (id: string, attId: string) =>
    api.get<Blob>(`/work-orders/${id}/attachments/${attId}/content`, { responseType: 'blob' }),
  searchAssets: (params: { centerId?: string; q?: string; size?: number }) =>
    api.get<{ content: AssetOption[] }>('/assets', { params: { size: 20, ...params } }),
};

export const notificationPrefsApi = {
  get: () => api.get<NotificationPreferences>('/notifications/preferences'),
  put: (body: { emailEnabled: boolean; smsEnabled: boolean; types?: string[] | null }) =>
    api.put<NotificationPreferences>('/notifications/preferences', body),
};

export interface DeliveryQuery { status?: string; channel?: string; from?: string; to?: string; page?: number; size?: number }

export const adminNotificationApi = {
  deliveries: (q: DeliveryQuery) => {
    const params: Record<string, string | number> = {};
    (Object.keys(q) as (keyof DeliveryQuery)[]).forEach((k) => {
      const v = q[k];
      if (v !== undefined && v !== '') params[k] = v;
    });
    return api.get<DeliveryPage>('/admin/notification-deliveries', { params });
  },
  providers: () => api.get<ProviderStatus[]>('/admin/notification-providers'),
};
