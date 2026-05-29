import axios from 'axios';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || '/api';

export const api = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('smms_token');
    if (token) config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && typeof window !== 'undefined') {
      localStorage.removeItem('smms_token');
      localStorage.removeItem('smms_user');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

// ── Auth ──────────────────────────────────────────────────
export const authApi = {
  login: (username: string, password: string) =>
    api.post('/auth/login', { username, password }),
};

// ── Dashboard ─────────────────────────────────────────────
export const dashboardApi = {
  getManagement: (centerId?: string) =>
    api.get('/dashboard/management', { params: centerId ? { centerId } : {} }),
  getControlRoom: () => api.get('/dashboard/control-room'),
  getTechnician:  () => api.get('/dashboard/technician'),
};

// ── Work Orders ───────────────────────────────────────────
export const workOrderApi = {
  getAll:     (params?: Record<string, unknown>) => api.get('/work-orders', { params }),
  getById:    (id: string) => api.get(`/work-orders/${id}`),
  create:     (data: unknown) => api.post('/work-orders', data),
  transition: (id: string, data: unknown) => api.post(`/work-orders/${id}/transition`, data),
  assign:     (id: string, technicianId: string, note?: string) =>
    api.post(`/work-orders/${id}/assign`, null, { params: { technicianId, ...(note ? { note } : {}) } }),
  warehouseAction: (id: string, materialsAvailable: boolean, notes?: string) =>
    api.post(`/work-orders/${id}/warehouse`, null, { params: { materialsAvailable, ...(notes ? { notes } : {}) } }),
  approvalDecision: (id: string, decision: string, note?: string) =>
    api.post(`/work-orders/${id}/approval`, null, { params: { decision, ...(note ? { note } : {}) } }),
  close:      (id: string, note?: string) =>
    api.post(`/work-orders/${id}/close`, null, { params: note ? { note } : {} }),
  reopen:     (id: string, note: string) =>
    api.post(`/work-orders/${id}/reopen`, null, { params: { note } }),
  getHistory: (id: string) => api.get(`/work-orders/${id}/history`),
  getAttachments: (id: string) => api.get(`/work-orders/${id}/attachments`),
};

// ── Users ─────────────────────────────────────────────────
export const userApi = {
  getAll:         (params?: Record<string, unknown>) => api.get('/admin/users', { params }),
  create:         (data: unknown) => api.post('/admin/users', data),
  update:         (id: string, data: unknown) => api.put(`/admin/users/${id}`, data),
  toggleActive:   (id: string, active: boolean) =>
    api.put(`/admin/users/${id}/active`, null, { params: { active } }),
  getTechnicians: (centerId?: string) =>
    api.get('/admin/users', { params: { role: 'technician', ...(centerId ? { centerId } : {}) } }),
  grantControlRoom: (userId: string, controlRoomId: string) =>
    api.post(`/admin/users/${userId}/control-rooms`, null, { params: { controlRoomId } }),
  resetPassword: (id: string, newPassword: string) =>
    api.post(`/admin/users/${id}/reset-password`, null, { params: { newPassword } }),
};

// ── Regions ───────────────────────────────────────────────
export const regionApi = {
  getAll:  () => api.get('/admin/regions'),
  create:  (data: unknown) => api.post('/admin/regions', data),
  update:  (id: string, data: unknown) => api.put(`/admin/regions/${id}`, data),
};

// ── Centers ───────────────────────────────────────────────
export const centerApi = {
  getAll:  (regionId?: string) =>
    api.get('/admin/centers', { params: regionId ? { regionId } : {} }),
  create:  (data: unknown) => api.post('/admin/centers', data),
  update:  (id: string, data: unknown) => api.put(`/admin/centers/${id}`, data),
};

// ── Control Rooms ─────────────────────────────────────────
export const controlRoomApi = {
  getAll:  (centerId?: string) =>
    api.get('/admin/control-rooms', { params: centerId ? { centerId } : {} }),
  create:  (data: unknown) => api.post('/admin/control-rooms', data),
  update:  (id: string, data: unknown) => api.put(`/admin/control-rooms/${id}`, data),
};

// ── Maintenance Types ─────────────────────────────────────
export const maintenanceTypeApi = {
  getAll:            ()                     => api.get('/maintenance-types'),
  getByControlRoom:  (controlRoomId: string) => api.get('/maintenance-types', { params: { controlRoomId } }),
  create:            (data: unknown)         => api.post('/maintenance-types', data),
  update:            (id: string, data: unknown) => api.put(`/maintenance-types/${id}`, data),
  delete:            (id: string)            => api.delete(`/maintenance-types/${id}`),
};

// ── Bulk Import ───────────────────────────────────────────
export const importApi = {
  downloadResidentTemplate:   () =>
    api.get('/import/template/residents', { responseType: 'blob' }),
  downloadDepartmentTemplate: () =>
    api.get('/import/template/departments', { responseType: 'blob' }),
  importResidents:   (file: File) => {
    const fd = new FormData(); fd.append('file', file);
    return api.post('/import/residents', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
  },
  importDepartments: (file: File) => {
    const fd = new FormData(); fd.append('file', file);
    return api.post('/import/departments', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
  },
};

// ── Me (current user profile) ─────────────────────────────
export const meApi = {
  get:            () => api.get('/users/me'),
  update:         (data: unknown) => api.put('/users/me', data),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post('/users/me/change-password', { currentPassword, newPassword }),
};

// ── Notifications ─────────────────────────────────────────
export const notificationApi = {
  list:        (page = 0, size = 20) => api.get('/notifications', { params: { page, size } }),
  unreadCount: () => api.get<number>('/notifications/unread-count'),
  readAll:     () => api.post('/notifications/read-all'),
};
