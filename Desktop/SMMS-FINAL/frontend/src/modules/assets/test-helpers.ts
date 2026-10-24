import { vi } from 'vitest';
import { api } from '@/lib/api';
import { useAuthStore } from '@/store/authStore';
import type { UserRole } from '@/lib/types';

export function signIn(role: UserRole) {
  useAuthStore.setState({
    user: { id: 'u1', username: 'tester', fullName: 'Tester', role, active: true },
  });
}

export function httpError(status: number, message = '') {
  return Object.assign(new Error(`HTTP ${status}`), { response: { status, data: { status, message } } });
}

type Handler = (config?: { params?: Record<string, unknown> }) => unknown;

/** Routes `api.get(url)` to handlers by exact path; unknown paths answer 404. */
export function routeGet(handlers: Record<string, Handler>) {
  vi.mocked(api.get).mockImplementation(((url: string, config?: { params?: Record<string, unknown> }) => {
    const h = handlers[url];
    if (!h) return Promise.reject(httpError(404));
    try {
      const out = h(config);
      return out instanceof Promise ? out.then((data) => ({ data })) : Promise.resolve({ data: out });
    } catch (e) {
      return Promise.reject(e);
    }
  }) as never);
}

export function page<T>(content: T[], extra: Partial<{ number: number; totalPages: number; totalElements: number }> = {}) {
  return { content, number: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra };
}

export const CENTER = { id: 'c1', name: 'Center One', nameAr: 'المركز الأول', code: 'C1' };

export const ASSET = {
  id: 'a1', assetCode: 'AST-001', name: 'Chiller', nameAr: 'مبرد', category: 'HVAC', manufacturer: null, model: null,
  serialNumber: null, location: 'Roof', status: 'active', criticality: 'high', installDate: '2024-01-10',
  warrantyExpiry: null, decommissionedAt: null, center: CENTER, department: null, openWorkOrders: 2,
  activeDowntime: false, version: 3,
};
