import { useAuthStore } from '@/store/authStore';
import type { UserRole } from '@/lib/types';

/** Test helper: sign in a fake user with the given role and language. */
export function signIn(role: UserRole, opts: { id?: string; lang?: 'ar' | 'en'; centerId?: string } = {}) {
  useAuthStore.setState({
    lang: opts.lang ?? 'en',
    user: {
      id: opts.id ?? 'u-1',
      username: 'tester',
      fullName: 'Tester',
      role,
      active: true,
      center: { id: opts.centerId ?? 'c-1', name: 'Center 1', nameAr: 'مركز 1', code: 'C1' },
    },
  });
}

/** Test helper: an axios-shaped rejection. */
export const httpError = (status: number, message?: string) => ({ response: { status, data: { status, message } } });

export const page = <T,>(content: T[], extra: Record<string, unknown> = {}) => ({
  content,
  number: 0,
  size: 20,
  totalElements: content.length,
  totalPages: 1,
  ...extra,
});
