import axios from 'axios';
import { api } from '@/lib/api';
import { IDEMPOTENCY_HEADER } from './idempotency';
import type { ExecResult, QueueError, QueueItem } from './queue';

/** Maps an axios failure to the queue's error classes. The message is the server's, when it sent one. */
export function classifyError(e: unknown): QueueError {
  if (!axios.isAxiosError(e)) return { class: 'network' };
  const status = e.response?.status;
  const raw: unknown = (e.response?.data as { message?: unknown } | undefined)?.message;
  const message = typeof raw === 'string' ? raw : undefined;
  if (status === undefined) return { class: 'network' }; // offline, DNS, timeout, aborted
  if (status === 401) return { class: 'auth', status, message };
  if (status === 404) return { class: 'notfound', status, message };
  if (status === 409 || status === 422) return { class: 'conflict', status, message };
  if (status === 403) return { class: 'forbidden', status, message };
  if (status === 408 || status === 425 || status === 429 || status >= 500) return { class: 'retry', status, message };
  return { class: 'invalid', status, message };
}

/** Sends one queued action with its stored Idempotency-Key. */
export async function executeItem(item: QueueItem): Promise<ExecResult> {
  const headers: Record<string, string> = { [IDEMPOTENCY_HEADER]: item.idempotencyKey };
  let data: unknown = item.json;
  if (item.file) {
    const form = new FormData();
    form.append('file', item.file.blob, item.file.name);
    data = form;
    headers['Content-Type'] = 'multipart/form-data';
  }
  try {
    const res = await api.request({
      method: 'post',
      url: item.url,
      params: item.params,
      data,
      headers,
      timeout: item.file ? 120_000 : 30_000,
    });
    return { ok: true, data: res.data, replayed: res.headers?.['idempotent-replayed'] === 'true' };
  } catch (e) {
    return { ok: false, error: classifyError(e) };
  }
}

/** After a 409/422: true when the ticket already has the status this action wanted. */
export async function reconcileItem(item: QueueItem): Promise<boolean | null> {
  if (!item.reconcile) return false;
  try {
    const res = await api.get(`/work-orders/${item.reconcile.workOrderId}`, { timeout: 15_000 });
    return res.data?.status === item.reconcile.status;
  } catch {
    return null;
  }
}
