import { api } from '@/lib/api';
import { clean, type PageData } from '@/modules/assets/lib';
import type { Department, DepartmentFormValues, Member, UserLite } from './types';

export interface DepartmentListParams {
  centerId?: string;
  active?: boolean | '';
  q?: string;
  page?: number;
  size?: number;
}

export const departmentsApi = {
  list: (p: DepartmentListParams) =>
    api.get<PageData<Department>>('/departments', { params: clean({ ...p }) }).then((r) => r.data),
  create: (v: DepartmentFormValues) =>
    api
      .post<Department>('/departments', { centerId: v.centerId, name: v.name.trim(), nameAr: v.nameAr.trim(), code: v.code.trim() })
      .then((r) => r.data),
  update: (id: string, v: DepartmentFormValues) =>
    api
      .put<Department>(`/departments/${id}`, {
        centerId: v.centerId, name: v.name.trim(), nameAr: v.nameAr.trim(), code: v.code.trim(), active: v.active,
      })
      .then((r) => r.data),
  deactivate: (id: string) => api.post<Department>(`/departments/${id}/deactivate`).then((r) => r.data),
  members: (id: string, page = 0, size = 10) =>
    api.get<PageData<Member>>(`/departments/${id}/members`, { params: { page, size } }).then((r) => r.data),
  /** Users of a center that can be linked (SA / RS / HS only; the server decides). */
  candidateUsers: (centerId: string) =>
    api
      .get<PageData<UserLite>>('/admin/users', { params: { centerId, role: 'department', size: 100 } })
      .then((r) => r.data),
  linkUser: (userId: string, departmentId: string | null) =>
    api.put<UserLite>(`/admin/users/${userId}/department`, { departmentId }).then((r) => r.data),
};
