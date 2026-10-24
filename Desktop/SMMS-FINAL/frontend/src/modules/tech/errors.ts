import axios from 'axios';
import type { QueueError } from '@/lib/offline/queue';
import { classifyError } from '@/lib/offline/executor';
import type { TFn } from '@/lib/module-i18n';
import type { TechKey } from './i18n';

export interface DescribedError {
  /** Localized, safe to show. */
  text: string;
  /** The server's own message (usually English), shown as a secondary detail. */
  detail?: string;
  status?: number;
}

/** Turns a queue error or a raw axios error into a localized message. 403/404/409/422 each get their own wording. */
export function describeError(e: unknown, t: TFn<TechKey>): DescribedError {
  const q: QueueError = isQueueError(e) ? e : classifyError(e);
  const detail = q.message;
  let text: string;
  switch (q.status) {
    case 403: text = t('errForbidden'); break;
    case 404: text = t('errNotFound'); break;
    case 409: text = t('errConflict'); break;
    case 422: text = t('errRejected'); break;
    case 400: text = t('errInvalid'); break;
    default:
      if (q.class === 'network') text = t('errNetwork');
      else if (q.class === 'retry') text = t('errServer');
      else text = t('errGeneric');
  }
  return { text, detail: detail && q.class !== 'network' ? detail : undefined, status: q.status };
}

function isQueueError(e: unknown): e is QueueError {
  return typeof e === 'object' && e !== null && 'class' in e && !axios.isAxiosError(e);
}

export function isNetworkError(e: unknown): boolean {
  return axios.isAxiosError(e) && e.response === undefined;
}
