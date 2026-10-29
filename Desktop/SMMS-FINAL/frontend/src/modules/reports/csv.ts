import { api } from '@/lib/api';
import { apiError, type ApiErr } from '@/modules/assets/lib';

/** Filename from `Content-Disposition: attachment; filename="x.csv"`, or the fallback. */
export function filenameFrom(disposition: unknown, fallback: string): string {
  if (typeof disposition !== 'string') return fallback;
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return m ? decodeURIComponent(m[1].trim()) : fallback;
}

function readBlob(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') return blob.text();
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result ?? ''));
    r.onerror = () => reject(r.error);
    r.readAsText(blob);
  });
}

/** With responseType blob an error body is a Blob too: read its JSON message. */
export async function csvError(err: unknown): Promise<ApiErr> {
  const base = apiError(err);
  const data = (err as { response?: { data?: unknown } })?.response?.data;
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const parsed = JSON.parse(await readBlob(data)) as { message?: unknown };
      if (typeof parsed.message === 'string') return { status: base.status, message: parsed.message };
    } catch {
      /* not JSON */
    }
  }
  return base;
}

/**
 * Downloads a report as CSV through the authenticated axios instance (Bearer header, never a token in
 * the URL), then hands the blob to the browser as a file download.
 */
export async function downloadCsv(path: string, params: Record<string, unknown>, fallbackName: string): Promise<string> {
  const res = await api.get<Blob>(path, { params: { ...params, format: 'csv' }, responseType: 'blob' });
  const name = filenameFrom((res.headers as Record<string, unknown> | undefined)?.['content-disposition'], fallbackName);
  const url = URL.createObjectURL(res.data);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return name;
}
