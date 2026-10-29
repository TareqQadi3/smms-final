import axios from 'axios';
import type { TFn } from '@/lib/module-i18n';
import type { RequestsKey } from './i18n';

export interface DescribedError { text: string; detail?: string; status?: number }

/** Localized message per status; the server's own message (usually English) is returned as `detail`. */
export function describeRequestError(e: unknown, t: TFn<RequestsKey>): DescribedError {
  if (!axios.isAxiosError(e)) return { text: t('errGeneric') };
  const status = e.response?.status;
  const raw: unknown = (e.response?.data as { message?: unknown } | undefined)?.message;
  const detail = typeof raw === 'string' ? raw : undefined;
  switch (status) {
    case undefined: return { text: t('errNetwork'), status };
    case 400: return { text: t('errInvalid'), detail, status };
    case 403: return { text: t('errForbidden'), status };
    case 404: return { text: t('errNotFound'), status };
    case 409: return { text: t('errConflict'), detail, status };
    case 422: return { text: t('errRejected'), detail, status };
    default: return { text: t('errGeneric'), status };
  }
}

/** Network failures and 5xx may have reached the server: an Idempotency-Key must be reused for the retry. */
export function isRetryable(e: unknown): boolean {
  if (!axios.isAxiosError(e)) return false;
  const s = e.response?.status;
  return s === undefined || s >= 500 || s === 408 || s === 429;
}
