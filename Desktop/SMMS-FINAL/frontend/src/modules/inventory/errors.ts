import { translate, type ModuleDict } from '@/lib/module-i18n';
import type { Lang } from '@/lib/i18n';

export type ApiErrorKind = 'forbidden' | 'notFound' | 'conflict' | 'unprocessable' | 'badRequest' | 'network' | 'server';

export interface MappedError {
  kind: ApiErrorKind;
  status?: number;
  /** Localized, user-facing text (server detail appended when available). */
  message: string;
  /** Raw server message (English) if any. */
  detail?: string;
}

const ar = {
  forbidden: 'ليست لديك صلاحية لتنفيذ هذا الإجراء.',
  notFound: 'العنصر غير موجود أو خارج نطاق صلاحياتك.',
  conflict: 'تعارض مع الحالة الحالية (409). أعد تحميل الصفحة ثم حاول مرة أخرى.',
  unprocessable: 'لا يمكن تنفيذ الإجراء في الحالة الحالية (422).',
  badRequest: 'بيانات غير صالحة.',
  network: 'تعذر الاتصال بالخادم. تحقق من الشبكة وحاول مرة أخرى.',
  server: 'حدث خطأ في الخادم. حاول مرة أخرى لاحقا.',
};
const en: Partial<typeof ar> = {
  forbidden: 'You do not have permission to perform this action.',
  notFound: 'Not found, or outside your scope.',
  conflict: 'Conflict with the current state (409). Reload and try again.',
  unprocessable: 'This action is not allowed in the current state (422).',
  badRequest: 'Invalid data.',
  network: 'Cannot reach the server. Check your connection and try again.',
  server: 'Server error. Please try again later.',
};
const dict: ModuleDict<keyof typeof ar> = { ar, en };

interface AxiosLike {
  response?: { status?: number; data?: { message?: string } };
  message?: string;
}

/** Maps an axios error to a localized message; the server's own message (English) is appended for 400/409/422. */
export function mapApiError(err: unknown, lang: Lang): MappedError {
  const e = (err ?? {}) as AxiosLike;
  const status = e.response?.status;
  const detail = typeof e.response?.data?.message === 'string' ? e.response.data.message : undefined;
  const t = (k: keyof typeof ar) => translate(dict, lang, k);
  if (status === undefined) return { kind: 'network', message: t('network') };
  const withDetail = (base: string) => (detail ? `${base} ${detail}` : base);
  if (status === 403) return { kind: 'forbidden', status, message: t('forbidden'), detail };
  if (status === 404) return { kind: 'notFound', status, message: t('notFound'), detail };
  if (status === 409) return { kind: 'conflict', status, message: withDetail(t('conflict')), detail };
  if (status === 422) return { kind: 'unprocessable', status, message: withDetail(t('unprocessable')), detail };
  if (status === 400) return { kind: 'badRequest', status, message: withDetail(t('badRequest')), detail };
  return { kind: 'server', status, message: t('server'), detail };
}
