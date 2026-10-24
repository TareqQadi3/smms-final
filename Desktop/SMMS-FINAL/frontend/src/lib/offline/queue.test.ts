import { describe, expect, it, vi } from 'vitest';
import { backoffDelay } from './backoff';
import { isValidIdempotencyKey, newIdempotencyKey } from './idempotency';
import {
  ActionQueue,
  localStorageStore,
  memoryStore,
  type ActionSpec,
  type ExecResult,
  type QueueItem,
  type QueueStore,
} from './queue';

const ok: ExecResult = { ok: true, data: { fine: true } };
const netFail: ExecResult = { ok: false, error: { class: 'network' } };

function spec(over: Partial<ActionSpec> = {}): ActionSpec {
  return {
    kind: 'transition',
    workOrderId: 'wo-1',
    url: '/work-orders/wo-1/transition',
    json: { targetStatus: 'in_progress' },
    reconcile: { workOrderId: 'wo-1', status: 'in_progress' },
    ...over,
  };
}

function setup(opts: { store?: QueueStore; online?: boolean; maxServerAttempts?: number } = {}) {
  const clock = { t: 1_000_000 };
  const timers: { fn: () => void; at: number }[] = [];
  const env = { online: opts.online ?? true };
  const script: ExecResult[] = [];
  const sent: QueueItem[] = [];
  const execute = vi.fn(async (item: QueueItem): Promise<ExecResult> => {
    sent.push(item);
    return script.shift() ?? ok;
  });
  const reconcile = vi.fn(async (): Promise<boolean | null> => false);
  const queue = new ActionQueue({
    store: opts.store ?? memoryStore(),
    execute,
    reconcile,
    isOnline: () => env.online,
    now: () => clock.t,
    random: () => 1, // no jitter: delay = base * 2^n
    setTimer: (fn, ms) => { const h = { fn, at: clock.t + ms }; timers.push(h); return h; },
    clearTimer: (h) => { const i = timers.indexOf(h as (typeof timers)[number]); if (i >= 0) timers.splice(i, 1); },
    maxServerAttempts: opts.maxServerAttempts,
  });
  const advance = async (ms: number) => {
    clock.t += ms;
    const due = timers.filter((x) => x.at <= clock.t);
    due.forEach((x) => timers.splice(timers.indexOf(x), 1));
    due.forEach((x) => x.fn());
    await queue.flush();
  };
  return { queue, execute, reconcile, script, sent, env, advance, timers, clock };
}

describe('idempotency keys', () => {
  it('generates valid unique keys', () => {
    const a = newIdempotencyKey();
    const b = newIdempotencyKey();
    expect(a).not.toBe(b);
    expect(isValidIdempotencyKey(a)).toBe(true);
    expect(isValidIdempotencyKey('short')).toBe(false);
    expect(isValidIdempotencyKey('has_underscore_1234')).toBe(false); // material requests reject '_'
  });
});

describe('backoff', () => {
  it('doubles up to the cap and jitters within [50%, 100%]', () => {
    expect([0, 1, 2, 3].map((n) => backoffDelay(n, { random: () => 1 }))).toEqual([1000, 2000, 4000, 8000]);
    expect(backoffDelay(20, { random: () => 1 })).toBe(60_000);
    expect(backoffDelay(2, { random: () => 0 })).toBe(2000);
  });
});

describe('ActionQueue', () => {
  it('sends immediately when online and reports done', async () => {
    const s = setup();
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('done');
    expect(s.execute).toHaveBeenCalledTimes(1);
    expect(s.queue.getSnapshot()).toHaveLength(0);
  });

  it('keeps the action while offline and replays it once online with the SAME Idempotency-Key', async () => {
    const s = setup({ online: false });
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('queued');
    expect(s.execute).not.toHaveBeenCalled();
    const [pending] = s.queue.getSnapshot();
    expect(pending.status).toBe('pending');

    s.env.online = true;
    await s.queue.flush();

    expect(s.execute).toHaveBeenCalledTimes(1);
    expect(s.sent[0].idempotencyKey).toBe(pending.idempotencyKey);
    expect(s.queue.getSnapshot()).toHaveLength(0);
  });

  it('retries a network failure with backoff and never changes the key or sends twice after success', async () => {
    const s = setup();
    s.script.push(netFail, netFail, ok);
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('queued');
    const key = s.sent[0].idempotencyKey;
    expect(s.queue.getSnapshot()[0].attempts).toBe(1);

    await s.advance(500); // before the 1 s backoff: nothing happens
    expect(s.execute).toHaveBeenCalledTimes(1);
    await s.advance(600); // 1.1 s: second attempt fails, next delay 2 s
    expect(s.execute).toHaveBeenCalledTimes(2);
    await s.advance(1_900);
    expect(s.execute).toHaveBeenCalledTimes(2);
    await s.advance(200);
    expect(s.execute).toHaveBeenCalledTimes(3);

    expect(new Set(s.sent.map((i) => i.idempotencyKey))).toEqual(new Set([key]));
    expect(s.queue.getSnapshot()).toHaveLength(0);
    await s.advance(120_000);
    expect(s.execute).toHaveBeenCalledTimes(3); // no duplicate submission
  });

  it('replays in FIFO order and a retryable head blocks the items behind it', async () => {
    const s = setup({ online: false });
    await s.queue.start('u1');
    await s.queue.enqueue(spec({ kind: 'note', url: '/n/1', json: { text: 'a' }, reconcile: undefined }));
    await s.queue.enqueue(spec({ kind: 'note', url: '/n/2', json: { text: 'b' }, reconcile: undefined }));
    await s.queue.enqueue(spec({ kind: 'note', url: '/n/3', json: { text: 'c' }, reconcile: undefined }));

    s.env.online = true;
    s.script.push(netFail); // head fails first
    await s.queue.flush();
    expect(s.sent.map((i) => i.url)).toEqual(['/n/1']); // 2 and 3 wait behind it

    await s.advance(1_100);
    expect(s.sent.map((i) => i.url)).toEqual(['/n/1', '/n/1', '/n/2', '/n/3']);
  });

  it('dedupes an action with the same dedupeKey (double tap)', async () => {
    const s = setup({ online: false });
    await s.queue.start('u1');
    const a = s.queue.enqueue(spec({ dedupeKey: 'transition:wo-1:in_progress' }));
    const b = s.queue.enqueue(spec({ dedupeKey: 'transition:wo-1:in_progress' }));
    await Promise.all([a, b]);
    expect(s.queue.getSnapshot()).toHaveLength(1);
    s.env.online = true;
    await s.queue.flush();
    expect(s.execute).toHaveBeenCalledTimes(1);
  });

  it('drops an item on 404', async () => {
    const s = setup();
    const events: string[] = [];
    s.queue.onEvent((e) => events.push(e.type));
    s.script.push({ ok: false, error: { class: 'notfound', status: 404 } });
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('failed');
    expect(s.queue.getSnapshot()).toHaveLength(0);
    expect(events).toContain('dropped');
  });

  it('on 422 re-reads the ticket: already in the target state counts as done', async () => {
    const s = setup();
    s.script.push({ ok: false, error: { class: 'conflict', status: 422, message: 'Transition not allowed' } });
    s.reconcile.mockResolvedValueOnce(true);
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out).toMatchObject({ state: 'done', reconciled: true });
    expect(s.queue.getSnapshot()).toHaveLength(0);
  });

  it('on 422 with a different server state the foreground caller gets the failure; a replayed one stays visible as failed', async () => {
    // foreground
    const s1 = setup();
    s1.script.push({ ok: false, error: { class: 'conflict', status: 422, message: 'nope' } });
    await s1.queue.start('u1');
    const out = await s1.queue.enqueue(spec());
    expect(out).toMatchObject({ state: 'failed', error: { status: 422, message: 'nope' } });
    expect(s1.queue.getSnapshot()).toHaveLength(0);

    // replay after being offline: visible failed item, queue goes on
    const s2 = setup({ online: false });
    await s2.queue.start('u1');
    await s2.queue.enqueue(spec());
    await s2.queue.enqueue(spec({ kind: 'note', url: '/n/2', json: { text: 'b' }, reconcile: undefined }));
    s2.env.online = true;
    s2.script.push({ ok: false, error: { class: 'conflict', status: 422, message: 'nope' } }, ok);
    await s2.queue.flush();
    const left = s2.queue.getSnapshot();
    expect(left).toHaveLength(1);
    expect(left[0]).toMatchObject({ status: 'failed', lastError: { status: 422 } });
    expect(s2.execute).toHaveBeenCalledTimes(2); // the note behind the failed head still went out
    // user presses retry: same key goes out again
    const key = left[0].idempotencyKey;
    s2.queue.retry(left[0].id);
    await s2.queue.flush();
    expect(s2.sent.at(-1)?.idempotencyKey).toBe(key);
  });

  it('pauses on 401 (session ended) and resumes after sign-in without losing the item', async () => {
    const s = setup();
    s.script.push({ ok: false, error: { class: 'auth', status: 401 } });
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('queued');
    expect(s.queue.isPaused()).toBe(true);
    expect(s.queue.getSnapshot()).toHaveLength(1);
    await s.advance(60_000);
    expect(s.execute).toHaveBeenCalledTimes(1); // nothing is sent while paused

    await s.queue.start('u1'); // same user signs in again
    await s.queue.flush();
    expect(s.execute).toHaveBeenCalledTimes(2);
    expect(s.sent[1].idempotencyKey).toBe(s.sent[0].idempotencyKey);
    expect(s.queue.getSnapshot()).toHaveLength(0);
  });

  it('gives up on repeated server errors after maxServerAttempts, but never on network errors', async () => {
    const s = setup({ maxServerAttempts: 3 });
    const serverFail: ExecResult = { ok: false, error: { class: 'retry', status: 503 } };
    s.script.push(serverFail, serverFail, serverFail);
    await s.queue.start('u1');
    await s.queue.enqueue(spec());
    await s.advance(2_000);
    await s.advance(5_000);
    expect(s.queue.getSnapshot()[0]).toMatchObject({ status: 'failed', attempts: 3 });
  });

  it('persists per user and a new session (reload) replays with the same key; other users see nothing', async () => {
    const mem = new Map<string, string>();
    const storage = {
      getItem: (k: string) => mem.get(k) ?? null,
      setItem: (k: string, v: string) => { mem.set(k, v); },
      removeItem: (k: string) => { mem.delete(k); },
    } as unknown as Storage;

    const first = setup({ online: false, store: localStorageStore(storage) });
    await first.queue.start('u1');
    await first.queue.enqueue(spec());
    const key = first.queue.getSnapshot()[0].idempotencyKey;

    // "reload": a fresh queue over the same storage
    const second = setup({ online: false, store: localStorageStore(storage) });
    await second.queue.start('u2');
    expect(second.queue.getSnapshot()).toHaveLength(0); // another user: nothing to replay
    second.queue.stop();
    await second.queue.start('u1');
    expect(second.queue.getSnapshot()).toHaveLength(1);
    second.env.online = true;
    await second.queue.flush();
    expect(second.sent).toHaveLength(1);
    expect(second.sent[0].idempotencyKey).toBe(key);
    expect(mem.size).toBe(0); // storage emptied after success
  });

  it('an item left in `sending` by a crash is sent again with the same key', async () => {
    const store = memoryStore();
    const stuck: QueueItem = {
      ...spec(), id: 'i1', idempotencyKey: 'key-from-before-crash-1', userId: 'u1',
      createdAt: 1, attempts: 0, nextAttemptAt: 0, status: 'sending',
    };
    await store.save('u1', [stuck]);
    const s = setup({ store });
    await s.queue.start('u1');
    await s.queue.flush();
    expect(s.sent[0].idempotencyKey).toBe('key-from-before-crash-1');
  });

  it('survives a failing store', async () => {
    const broken: QueueStore = {
      load: async () => { throw new Error('boom'); },
      save: async () => { throw new Error('boom'); },
    };
    const s = setup({ store: broken });
    await s.queue.start('u1');
    const out = await s.queue.enqueue(spec());
    expect(out.state).toBe('done');
  });
});
