import { api, controlRoomApi } from '@/lib/api';
import { clean, type PageData } from '@/modules/assets/lib';
import type { ControlRoomLite, GenerateResult, Plan, PlanFormValues, PlanListParams, PlanRun } from './types';

function common(v: PlanFormValues) {
  return {
    controlRoomId: v.controlRoomId,
    subType: v.subType,
    priority: v.priority || null,
    title: v.title.trim(),
    instructions: v.instructions.trim() === '' ? null : v.instructions.trim(),
    frequencyUnit: v.frequencyUnit,
    frequencyInterval: Number(v.frequencyInterval),
    leadDays: v.leadDays === '' ? 0 : Number(v.leadDays),
  };
}

export const pmApi = {
  list: (p: PlanListParams) => api.get<PageData<Plan>>('/maintenance-plans', { params: clean({ ...p }) }).then((r) => r.data),
  get: (id: string) => api.get<Plan>(`/maintenance-plans/${id}`).then((r) => r.data),
  runs: (id: string, page = 0, size = 10) =>
    api.get<PageData<PlanRun>>(`/maintenance-plans/${id}/runs`, { params: { page, size } }).then((r) => r.data),
  create: (v: PlanFormValues) =>
    api.post<Plan>('/maintenance-plans', { assetId: v.assetId, ...common(v), startDate: v.startDate }).then((r) => r.data),
  update: (id: string, v: PlanFormValues, version: number) =>
    api
      .put<Plan>(`/maintenance-plans/${id}`, { version, ...common(v), nextDueDate: v.nextDueDate || null })
      .then((r) => r.data),
  activate: (id: string) => api.post<Plan>(`/maintenance-plans/${id}/activate`).then((r) => r.data),
  deactivate: (id: string) => api.post<Plan>(`/maintenance-plans/${id}/deactivate`).then((r) => r.data),
  remove: (id: string) => api.delete(`/maintenance-plans/${id}`).then(() => undefined),
  generateNow: (id: string) => api.post<GenerateResult>(`/maintenance-plans/${id}/generate-now`).then((r) => r.data),
  controlRooms: (centerId: string) =>
    controlRoomApi.getAll(centerId).then((r) => ((r.data ?? []) as ControlRoomLite[]).filter((c) => c.active !== false)),
};
