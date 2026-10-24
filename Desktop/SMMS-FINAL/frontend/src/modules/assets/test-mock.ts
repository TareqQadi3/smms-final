import { vi } from 'vitest';

/** Factory for `vi.mock('@/lib/api', ...)`: only what the FB modules (and authStore) import. */
export function apiMockFactory() {
  return {
    api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
    authApi: { login: vi.fn(), logout: vi.fn(() => Promise.resolve()) },
    centerApi: { getAll: vi.fn() },
    regionApi: { getAll: vi.fn() },
    controlRoomApi: { getAll: vi.fn() },
  };
}
