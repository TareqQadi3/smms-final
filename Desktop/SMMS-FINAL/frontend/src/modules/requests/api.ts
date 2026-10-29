import { api, publicApi } from '@/lib/api';
import type { PageResponse } from '@/lib/types';
import type {
  InfoRequest, PublicStatus, Rating, RequestHistoryEntry, RequestWorkOrder, StatusLink,
} from './types';

export const requestsApi = {
  list: (params: { page: number; size: number; status?: string }) =>
    api.get<PageResponse<RequestWorkOrder>>('/work-orders', { params: { ...params, sort: 'createdAt,desc' } }),
  get: (id: string) => api.get<RequestWorkOrder>(`/work-orders/${id}`),
  history: (id: string) => api.get<RequestHistoryEntry[]>(`/work-orders/${id}/history`),
  infoRequests: (id: string) => api.get<InfoRequest[]>(`/work-orders/${id}/info-requests`),
  provideInfo: (id: string, note: string) => api.post(`/work-orders/${id}/provide-info`, { note }),
  /** `key` is an Idempotency-Key: a retry with the same key and body is answered with 200 + Idempotent-Replayed. */
  confirm: (id: string, accepted: boolean, note: string | undefined, key: string) =>
    api.post<RequestWorkOrder>(`/work-orders/${id}/confirm`, { accepted, ...(note ? { note } : {}) }, { headers: { 'Idempotency-Key': key } }),
  getRating: (id: string) => api.get<Rating>(`/work-orders/${id}/rating`),
  rate: (id: string, score: number, comment?: string) =>
    api.post<Rating>(`/work-orders/${id}/rating`, { score, ...(comment ? { comment } : {}) }),
  /** Token is returned once; creating a new link revokes the previous one. */
  createStatusLink: (id: string) => api.post<StatusLink>(`/work-orders/${id}/status-link`),
};

/** Public, credential-free. Unknown, expired, revoked, malformed token and a disabled feature are all the same 404. */
export const publicStatusApi = {
  get: (token: string) => publicApi.get<PublicStatus>(`/public/request-status/${encodeURIComponent(token)}`),
};
