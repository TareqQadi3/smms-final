import axios, { AxiosError, AxiosHeaders, InternalAxiosRequestConfig } from 'axios';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || '/api';

// ── Session storage ───────────────────────────────────────
// Access token, refresh token and the cached profile live in localStorage. Tokens are never logged.
export const STORAGE_KEYS = {
  access: 'smms_token',
  refresh: 'smms_refresh_token',
  user: 'smms_user',
} as const;

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null; // blocked storage (private mode, policy)
  }
}

export function readAccessToken(): string | null {
  try { return storage()?.getItem(STORAGE_KEYS.access) ?? null; } catch { return null; }
}

export function readRefreshToken(): string | null {
  try { return storage()?.getItem(STORAGE_KEYS.refresh) ?? null; } catch { return null; }
}

/** Persists the access token and, when given, the (rotated) refresh token. */
export function storeTokens(access: string, refresh?: string | null): void {
  endingSession = false;
  const s = storage();
  if (!s) return;
  try {
    s.setItem(STORAGE_KEYS.access, access);
    if (refresh) s.setItem(STORAGE_KEYS.refresh, refresh);
  } catch { /* quota or blocked: the session then lasts until reload */ }
}

export function clearSession(): void {
  const s = storage();
  if (!s) return;
  try {
    s.removeItem(STORAGE_KEYS.access);
    s.removeItem(STORAGE_KEYS.refresh);
    s.removeItem(STORAGE_KEYS.user);
  } catch { /* ignore */ }
}

// ── Session events (the auth store subscribes; no import cycle) ──
export type SessionEvent =
  | { type: 'refreshed'; token: string }
  | { type: 'ended'; reason: 'expired' };

const listeners = new Set<(e: SessionEvent) => void>();

export function subscribeSession(fn: (e: SessionEvent) => void): () => void {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
}

function emit(e: SessionEvent) {
  listeners.forEach((fn) => { try { fn(e); } catch { /* a listener must not break the session flow */ } });
}

/** Only same-site absolute paths are accepted as a post-login destination (no open redirect). */
export function safeNextPath(next: string | null | undefined): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.includes('\\')) return null;
  if (next === '/login' || next.startsWith('/login?') || next.startsWith('/login/')) return null;
  return next;
}

export function buildLoginUrl(currentPath: string | null, expired: boolean): string {
  const params = new URLSearchParams();
  if (expired) params.set('expired', '1');
  const next = safeNextPath(currentPath);
  if (next) params.set('next', next);
  const q = params.toString();
  return q ? `/login?${q}` : '/login';
}

// Overridable for tests; the default is a full navigation so every in-memory cache is dropped.
export const navigation = {
  assign(url: string) { window.location.assign(url); },
  currentPath(): string | null {
    return typeof window === 'undefined' ? null : window.location.pathname + window.location.search;
  },
};

let endingSession = false;

/** Clears the session once and sends the user to /login, remembering where they were. */
export function endSession(): void {
  if (endingSession) return;
  endingSession = true;
  clearSession();
  emit({ type: 'ended', reason: 'expired' });
  if (typeof window === 'undefined') return;
  const path = navigation.currentPath();
  // Public pages and the login page itself never bounce to /login
  if (path && (path.startsWith('/login') || path.startsWith('/status/'))) return;
  navigation.assign(buildLoginUrl(path, true));
}

export const api = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

/** Client for endpoints that must never carry credentials (public status page). */
export const publicApi = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
  timeout: 20_000,
});

// ── Single-flight refresh ─────────────────────────────────
// The backend rotates the refresh token on every call and treats a reused one as theft (whole
// session revoked, no grace window). So exactly one refresh may be in flight: every 401 that
// arrives meanwhile waits for the same promise, then retries once with the new access token.

export class SessionExpiredError extends Error {
  constructor(public readonly reason: string) {
    super('session expired');
    this.name = 'SessionExpiredError';
  }
}

let inflight: Promise<string> | null = null;

type LockManagerLike = { request<T>(name: string, cb: () => Promise<T>): Promise<T> };

/** Serialises refreshes across tabs (they share the one rotating refresh token in localStorage). */
async function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined'
    ? (navigator as unknown as { locks?: LockManagerLike }).locks
    : undefined;
  if (!locks || typeof locks.request !== 'function') return fn();
  return locks.request('smms-token-refresh', fn);
}

async function doRefresh(failedToken: string | null): Promise<string> {
  return withCrossTabLock(async () => {
    // Another tab may have rotated the tokens while this one waited for the lock
    const current = readAccessToken();
    if (failedToken && current && current !== failedToken) return current;

    const refresh = readRefreshToken();
    if (!refresh) throw new SessionExpiredError('no-refresh-token');
    try {
      const res = await axios.post(`${API_BASE}/auth/refresh`, null, {
        headers: { 'X-Refresh-Token': refresh },
        timeout: 15_000,
      });
      const token: unknown = res.data?.token;
      const rotated: unknown = res.data?.refreshToken;
      if (typeof token !== 'string' || typeof rotated !== 'string' || !token || !rotated) {
        throw new SessionExpiredError('bad-refresh-response');
      }
      // Persist BEFORE anything else uses the new pair: the old refresh token is already dead
      storeTokens(token, rotated);
      emit({ type: 'refreshed', token });
      return token;
    } catch (e) {
      if (e instanceof SessionExpiredError) throw e;
      const status = axios.isAxiosError(e) ? e.response?.status : undefined;
      // 401 = invalid, expired, revoked or reused refresh token: the session is over
      if (status === 401 || status === 403) throw new SessionExpiredError('refresh-rejected');
      throw e; // network error or 5xx: keep the session, the caller may try again later
    }
  });
}

/** Shared refresh. `failedToken` is the access token the rejected request was sent with. */
export function refreshAccessToken(failedToken: string | null = null): Promise<string> {
  if (!inflight) {
    inflight = doRefresh(failedToken).finally(() => { inflight = null; });
  }
  return inflight;
}

/** Test helper: forget a pending refresh. */
export function resetSessionStateForTests(): void {
  inflight = null;
  endingSession = false;
}

api.interceptors.request.use((config) => {
  if (!config.headers.has('Authorization')) {
    const token = readAccessToken();
    if (token) config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

type Retriable = InternalAxiosRequestConfig & { _retried?: boolean };

function isAuthCall(url: string): boolean {
  return url.endsWith('/auth/login') || url.endsWith('/auth/logout') || url.endsWith('/auth/refresh');
}

function bearerOf(config: InternalAxiosRequestConfig): string | null {
  const h = AxiosHeaders.from(config.headers).get('Authorization');
  return typeof h === 'string' && h.startsWith('Bearer ') ? h.slice(7) : null;
}

api.interceptors.response.use(
  (res) => res,
  async (err: AxiosError) => {
    const original = err.config as Retriable | undefined;
    const status = err.response?.status;
    // A 401 from login/logout/refresh is a normal answer, not an expired session
    if (status !== 401 || !original || isAuthCall(original.url ?? '')) return Promise.reject(err);

    if (original._retried) {
      // The fresh token was rejected too: nothing left to try
      endSession();
      return Promise.reject(err);
    }
    try {
      const token = await refreshAccessToken(bearerOf(original));
      original._retried = true;
      original.headers.set('Authorization', `Bearer ${token}`);
      return api(original);
    } catch (refreshErr) {
      if (refreshErr instanceof SessionExpiredError) {
        endSession();
        return Promise.reject(err);
      }
      return Promise.reject(refreshErr);
    }
  }
);

// ── Auth ──────────────────────────────────────────────────
export const authApi = {
  login: (username: string, password: string) =>
    api.post('/auth/login', { username, password }).then((res) => {
      // The login page only passes the access token to the store; keep the refresh token here
      if (res.data?.token && res.data?.refreshToken) storeTokens(res.data.token, res.data.refreshToken);
      return res;
    }),
  // Fire-and-forget: the server revokes the session, the client clears its storage regardless.
  logout: (token?: string | null, refreshToken?: string | null) => {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (refreshToken) headers['X-Refresh-Token'] = refreshToken;
    return api.post('/auth/logout', null, { headers, timeout: 8_000 });
  },
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
  // Return for information (stage info_requested, status stays under_review)
  requestInfo: (id: string, note: string) => api.post(`/work-orders/${id}/request-info`, { note }),
  provideInfo: (id: string, note: string) => api.post(`/work-orders/${id}/provide-info`, { note }),
  getPossibleDuplicates: (id: string) => api.get(`/work-orders/${id}/possible-duplicates`),
  getHistory: (id: string) => api.get(`/work-orders/${id}/history`),
  // Technician picker: active technicians of the ticket's center (does not need the user list)
  getAssignableTechnicians: (id: string) =>
    api.get(`/work-orders/${id}/assignable-technicians`),
  getAttachments: (id: string) => api.get(`/work-orders/${id}/attachments`),
  // Blob download with the Bearer header (never put the token in a URL). Needs the backend endpoint.
  downloadAttachment: (id: string, attachmentId: string) =>
    api.get<Blob>(`/work-orders/${id}/attachments/${attachmentId}/content`, { responseType: 'blob' }),
};

// ── Users ─────────────────────────────────────────────────
export const userApi = {
  getAll:         (params?: Record<string, unknown>) => api.get('/admin/users', { params }),
  create:         (data: unknown) => api.post('/admin/users', data),
  update:         (id: string, data: unknown) => api.put(`/admin/users/${id}`, data),
  toggleActive:   (id: string, active: boolean) =>
    api.put(`/admin/users/${id}/active`, null, { params: { active } }),
  grantControlRoom: (userId: string, controlRoomId: string) =>
    api.post(`/admin/users/${userId}/control-rooms`, null, { params: { controlRoomId } }),
  resetPassword: (id: string, newPassword: string) =>
    api.post(`/admin/users/${id}/reset-password`, { newPassword }),
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
  markRead:    (id: string) => api.post(`/notifications/${id}/read`),
};
