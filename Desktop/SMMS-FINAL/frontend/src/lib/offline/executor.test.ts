import axios, { AxiosError, type AxiosAdapter } from 'axios';
import { describe, expect, it } from 'vitest';
import { api } from '@/lib/api';
import { classifyError, executeItem, reconcileItem } from './executor';
import type { QueueItem } from './queue';

function item(over: Partial<QueueItem> = {}): QueueItem {
  return {
    id: 'i', idempotencyKey: '11111111-2222-4333-8444-555555555555', userId: 'u', createdAt: 0, attempts: 0,
    nextAttemptAt: 0, status: 'pending', kind: 'note', url: '/work-orders/w/notes', json: { text: 'x' }, ...over,
  };
}

function fail(status: number, data: unknown = {}) {
  const config = { headers: {} } as never;
  return new AxiosError('x', 'ERR', config, null, { status, data, statusText: '', headers: {}, config });
}

describe('classifyError', () => {
  it('maps statuses to queue classes', () => {
    expect(classifyError(new AxiosError('Network Error', 'ERR_NETWORK')).class).toBe('network');
    expect(classifyError(new Error('x')).class).toBe('network');
    expect(classifyError(fail(401)).class).toBe('auth');
    expect(classifyError(fail(403)).class).toBe('forbidden');
    expect(classifyError(fail(404)).class).toBe('notfound');
    expect(classifyError(fail(409)).class).toBe('conflict');
    expect(classifyError(fail(422, { message: 'Wrong status' }))).toEqual({ class: 'conflict', status: 422, message: 'Wrong status' });
    expect(classifyError(fail(400)).class).toBe('invalid');
    expect(classifyError(fail(429)).class).toBe('retry');
    expect(classifyError(fail(503)).class).toBe('retry');
  });
});

describe('executeItem', () => {
  it('sends the stored Idempotency-Key and the JSON body', async () => {
    let seen: { key?: string; body?: unknown; method?: string } = {};
    api.defaults.adapter = (async (config) => {
      seen = { key: config.headers.get('Idempotency-Key') as string, body: config.data, method: config.method };
      return { data: { id: 1 }, status: 201, statusText: '', headers: {}, config };
    }) as AxiosAdapter;
    const res = await executeItem(item());
    expect(res.ok).toBe(true);
    expect(seen.key).toBe('11111111-2222-4333-8444-555555555555');
    expect(seen.method).toBe('post');
    expect(JSON.parse(seen.body as string)).toEqual({ text: 'x' });
  });

  it('uploads a photo as multipart with the phase query parameter', async () => {
    let seen: { params?: unknown; isForm?: boolean; file?: unknown } = {};
    api.defaults.adapter = (async (config) => {
      seen = { params: config.params, isForm: config.data instanceof FormData, file: (config.data as FormData).get('file') };
      return { data: '', status: 201, statusText: '', headers: {}, config };
    }) as AxiosAdapter;
    const blob = new Blob(['jpegbytes'], { type: 'image/jpeg' });
    const res = await executeItem(item({ kind: 'photo', url: '/work-orders/w/attachments', params: { phase: 'before' }, json: undefined, file: { blob, name: 'a.jpg', type: 'image/jpeg' } }));
    expect(res.ok).toBe(true);
    expect(seen.params).toEqual({ phase: 'before' });
    expect(seen.isForm).toBe(true);
    expect((seen.file as File).name).toBe('a.jpg');
  });

  it('classifies failures and flags replays', async () => {
    api.defaults.adapter = (async () => { throw fail(422, { message: 'Idempotency-Key was already used' }); }) as AxiosAdapter;
    expect(await executeItem(item())).toEqual({ ok: false, error: { class: 'conflict', status: 422, message: 'Idempotency-Key was already used' } });

    api.defaults.adapter = (async (config) => ({ data: {}, status: 200, statusText: '', headers: { 'idempotent-replayed': 'true' }, config })) as AxiosAdapter;
    expect(await executeItem(item())).toMatchObject({ ok: true, replayed: true });
  });
});

describe('reconcileItem', () => {
  it('compares the server status with the wanted one; null when unreachable', async () => {
    api.defaults.adapter = (async (config) => ({ data: { status: 'in_progress' }, status: 200, statusText: '', headers: {}, config })) as AxiosAdapter;
    expect(await reconcileItem(item({ reconcile: { workOrderId: 'w', status: 'in_progress' } }))).toBe(true);
    expect(await reconcileItem(item({ reconcile: { workOrderId: 'w', status: 'completed' } }))).toBe(false);
    api.defaults.adapter = (async () => { throw new AxiosError('Network Error', 'ERR_NETWORK'); }) as AxiosAdapter;
    expect(await reconcileItem(item({ reconcile: { workOrderId: 'w', status: 'completed' } }))).toBeNull();
    void axios;
  });
});
