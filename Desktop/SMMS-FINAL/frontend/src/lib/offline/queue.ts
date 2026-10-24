import { backoffDelay } from './backoff';
import { newIdempotencyKey } from './idempotency';

/**
 * Offline action queue for the technician area.
 *
 * - Every mutating technician action (status change, note, material consumption, photo) is first
 *   persisted, then sent. If the network fails the item stays and is retried later with the SAME
 *   Idempotency-Key, so a request that reached the server but whose answer was lost is never
 *   applied twice (the server answers 200 + Idempotent-Replayed).
 * - Strict FIFO: a retryable failure at the head stops the pass; later items wait behind it.
 *   Permanently failed items stay visible (status `failed`) and do not block the rest.
 * - Per error class: network/5xx/429 retry with exponential backoff; 401 (session ended) pauses the
 *   queue until the user signs in again; 404 drops the item (access lost or ticket gone); 409/422
 *   re-reads the ticket and counts the item as done when the server already is in the target
 *   state, otherwise it is shown as failed with the server message; 403/400 fail.
 * - Persisted per user (IndexedDB, falling back to localStorage, falling back to memory). Photos
 *   (Blobs) survive a reload only with IndexedDB.
 */

export type ActionKind = 'transition' | 'note' | 'consume' | 'photo';
export type ItemStatus = 'pending' | 'sending' | 'failed';
export type ErrorClass = 'network' | 'retry' | 'auth' | 'notfound' | 'conflict' | 'forbidden' | 'invalid';

export interface QueueFile {
  blob: Blob;
  name: string;
  type: string;
}

export interface ActionSpec {
  kind: ActionKind;
  workOrderId?: string;
  url: string;
  params?: Record<string, string>;
  json?: unknown;
  file?: QueueFile;
  /** Short, non-localized facts for the UI (ticket number, target status, note excerpt...). */
  meta?: Record<string, string>;
  /** An active item with the same dedupeKey is returned instead of enqueuing a second one. */
  dedupeKey?: string;
  /** On 409/422 the ticket is re-read; the item counts as done if its status already equals this. */
  reconcile?: { workOrderId: string; status: string };
}

export interface QueueError {
  class: ErrorClass;
  status?: number;
  message?: string;
}

export interface QueueItem extends ActionSpec {
  id: string;
  idempotencyKey: string;
  userId: string;
  createdAt: number;
  attempts: number;
  nextAttemptAt: number;
  status: ItemStatus;
  lastError?: QueueError;
}

export type ExecResult =
  | { ok: true; data?: unknown; replayed?: boolean }
  | { ok: false; error: QueueError };

export type ActionOutcome =
  | { state: 'done'; item: QueueItem; data?: unknown; reconciled?: boolean }
  | { state: 'queued'; item: QueueItem }
  | { state: 'failed'; item: QueueItem; error: QueueError };

export type QueueEvent =
  | { type: 'done'; item: QueueItem; data?: unknown }
  | { type: 'dropped'; item: QueueItem; reason: string }
  | { type: 'failed'; item: QueueItem };

export interface QueueStore {
  load(userId: string): Promise<QueueItem[]>;
  save(userId: string, items: QueueItem[]): Promise<void>;
}

export interface QueueDeps {
  store: QueueStore;
  execute: (item: QueueItem) => Promise<ExecResult>;
  /** Returns true when the server already is in `item.reconcile.status`; null when it could not tell. */
  reconcile: (item: QueueItem) => Promise<boolean | null>;
  isOnline: () => boolean;
  now?: () => number;
  random?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (h: unknown) => void;
  /** Gives up on retryable SERVER errors (5xx/429) after this many attempts. Network errors never give up. */
  maxServerAttempts?: number;
}

const DEFAULT_MAX_SERVER_ATTEMPTS = 8;

export class ActionQueue {
  private items: QueueItem[] = [];
  private snapshot: readonly QueueItem[] = [];
  private userId: string | null = null;
  private paused = false;
  private running: Promise<void> | null = null;
  private again = false;
  private timer: unknown = null;
  private disposed = false;
  private readonly listeners = new Set<() => void>();
  private readonly eventListeners = new Set<(e: QueueEvent) => void>();
  private readonly waiters = new Map<string, (o: ActionOutcome) => void>();
  private lastSaved: Promise<void> = Promise.resolve();

  constructor(private readonly deps: QueueDeps) {}

  // ── observable state (useSyncExternalStore) ──
  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn);
    return () => { this.listeners.delete(fn); };
  };
  getSnapshot = (): readonly QueueItem[] => this.snapshot;

  onEvent(fn: (e: QueueEvent) => void): () => void {
    this.eventListeners.add(fn);
    return () => { this.eventListeners.delete(fn); };
  }

  isPaused(): boolean { return this.paused; }
  currentUser(): string | null { return this.userId; }

  private now(): number { return (this.deps.now ?? Date.now)(); }

  private publish() {
    this.snapshot = this.items.slice();
    this.listeners.forEach((l) => l());
  }

  private emit(e: QueueEvent) {
    this.eventListeners.forEach((l) => { try { l(e); } catch { /* UI listener errors must not stop the queue */ } });
  }

  private persist(): Promise<void> {
    const userId = this.userId;
    if (!userId) return this.lastSaved;
    const copy = this.items.slice();
    // Saves are chained so an older write can never land after a newer one
    this.lastSaved = this.lastSaved
      .then(() => this.deps.store.save(userId, copy))
      .catch(() => { /* storage failure: the queue keeps working in memory */ });
    return this.lastSaved;
  }

  /** Loads the persisted items of `userId` and starts processing. Same user again: resume after a pause. */
  async start(userId: string): Promise<void> {
    if (this.userId === userId) {
      this.paused = false;
      this.kick();
      return;
    }
    this.clearTimer();
    this.userId = userId;
    this.paused = false;
    let loaded: QueueItem[] = [];
    try {
      loaded = await this.deps.store.load(userId);
    } catch { /* unreadable store: start empty */ }
    if (this.userId !== userId) return; // user switched while loading
    // A crash mid-send leaves `sending`: safe to resend, the Idempotency-Key is the same
    this.items = loaded
      .filter((i) => i.userId === userId)
      .map((i) => (i.status === 'sending' ? { ...i, status: 'pending' as const } : i));
    this.publish();
    this.kick();
  }

  /** Forgets in-memory state (user logged out); the persisted items stay for the next sign-in. */
  stop(): void {
    this.clearTimer();
    this.userId = null;
    this.items = [];
    this.paused = false;
    this.publish();
  }

  dispose(): void {
    this.disposed = true;
    this.clearTimer();
  }

  /** Session ended (401 after refresh failed): keep everything, send nothing until sign-in. */
  pause(): void {
    this.paused = true;
    this.clearTimer();
  }

  /** Call on `online`, on tab focus and after sign-in. */
  kick(): void {
    void this.flush();
  }

  // ── public mutations ──
  async enqueue(spec: ActionSpec): Promise<ActionOutcome> {
    if (!this.userId) throw new Error('offline queue is not started');
    if (spec.dedupeKey) {
      const existing = this.items.find((i) => i.dedupeKey === spec.dedupeKey && i.status !== 'failed');
      if (existing) {
        return new Promise<ActionOutcome>((resolve) => {
          const prev = this.waiters.get(existing.id);
          this.waiters.set(existing.id, (o) => { prev?.(o); resolve(o); });
          void this.flush().then(() => this.settleQueued(existing.id));
        });
      }
    }
    const item: QueueItem = {
      ...spec,
      id: newIdempotencyKey(),
      idempotencyKey: newIdempotencyKey(),
      userId: this.userId,
      createdAt: this.now(),
      attempts: 0,
      nextAttemptAt: this.now(),
      status: 'pending',
    };
    this.items.push(item);
    this.publish();
    // Persist BEFORE the first send so a crash cannot lose an action the server may already have
    await this.persist();
    const outcome = new Promise<ActionOutcome>((resolve) => { this.waiters.set(item.id, resolve); });
    await this.flush();
    // Still unresolved after the passes: offline, paused or behind an item that must be retried first
    this.settleQueued(item.id);
    return outcome;
  }

  /** Puts a failed item back in line (same Idempotency-Key). */
  retry(id: string): void {
    const item = this.items.find((i) => i.id === id);
    if (!item || item.status !== 'failed') return;
    this.replace({ ...item, status: 'pending', nextAttemptAt: this.now(), lastError: undefined });
    void this.persist();
    this.kick();
  }

  discard(id: string): void {
    const before = this.items.length;
    this.items = this.items.filter((i) => i.id !== id);
    if (this.items.length === before) return;
    this.waiters.delete(id);
    this.publish();
    void this.persist();
  }

  // ── processing ──
  /** Runs passes until nothing more is due. The promise covers every pass requested meanwhile. */
  flush(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    // `running` is cleared in the same synchronous step that finds `again` false, so a flush()
    // arriving at any other moment either sets `again` before the check or starts a new pass.
    this.running = (async () => {
      try {
        do {
          this.again = false;
          await this.run();
        } while (this.again && !this.disposed);
      } finally {
        this.running = null;
      }
    })();
    return this.running;
  }

  private replace(next: QueueItem) {
    this.items = this.items.map((i) => (i.id === next.id ? next : i));
    this.publish();
  }

  private remove(id: string) {
    this.items = this.items.filter((i) => i.id !== id);
    this.publish();
  }

  private settle(id: string, outcome: ActionOutcome) {
    const w = this.waiters.get(id);
    if (w) {
      this.waiters.delete(id);
      w(outcome);
    }
  }

  private settleQueued(id: string) {
    const item = this.items.find((i) => i.id === id);
    if (item) this.settle(id, { state: 'queued', item });
  }

  private clearTimer() {
    if (this.timer != null) {
      (this.deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>)))(this.timer);
      this.timer = null;
    }
  }

  private schedule(at: number) {
    this.clearTimer();
    const ms = Math.max(0, at - this.now());
    this.timer = (this.deps.setTimer ?? ((fn, d) => setTimeout(fn, d)))(() => {
      this.timer = null;
      this.kick();
    }, ms);
  }

  private async run(): Promise<void> {
    for (;;) {
      if (this.disposed || this.paused || !this.userId) return;
      if (!this.deps.isOnline()) return; // the `online` event kicks the queue again
      const head = this.items.find((i) => i.status !== 'failed');
      if (!head) return;
      if (head.nextAttemptAt > this.now()) {
        this.schedule(head.nextAttemptAt);
        return;
      }
      await this.attempt(head);
    }
  }

  /** Sends one item and records the result; the run loop then decides whether to go on. */
  private async attempt(item: QueueItem): Promise<void> {
    const sending: QueueItem = { ...item, status: 'sending' };
    this.replace(sending);
    let result: ExecResult;
    try {
      result = await this.deps.execute(sending);
    } catch {
      result = { ok: false, error: { class: 'network' } };
    }
    if (this.userId !== item.userId) return; // signed out mid-flight; the item is persisted

    if (result.ok) {
      this.remove(item.id);
      void this.persist();
      this.emit({ type: 'done', item: sending, data: result.data });
      this.settle(item.id, { state: 'done', item: sending, data: result.data });
      return;
    }

    const error = result.error;
    switch (error.class) {
      case 'auth': {
        this.replace({ ...sending, status: 'pending', lastError: error });
        void this.persist();
        this.pause();
        this.settle(item.id, { state: 'queued', item: { ...sending, status: 'pending', lastError: error } });
        return;
      }
      case 'network':
      case 'retry': {
        const attempts = item.attempts + 1;
        const max = this.deps.maxServerAttempts ?? DEFAULT_MAX_SERVER_ATTEMPTS;
        if (error.class === 'retry' && attempts >= max) {
          this.fail(sending, error, attempts);
          return;
        }
        const delay = backoffDelay(attempts - 1, { random: this.deps.random });
        const next: QueueItem = {
          ...sending, status: 'pending', attempts, lastError: error, nextAttemptAt: this.now() + delay,
        };
        this.replace(next);
        void this.persist();
        this.settle(item.id, { state: 'queued', item: next });
        return;
      }
      case 'notfound': {
        this.remove(item.id);
        void this.persist();
        this.emit({ type: 'dropped', item: sending, reason: 'not-found' });
        this.settle(item.id, { state: 'failed', item: sending, error });
        return;
      }
      case 'conflict': {
        if (sending.reconcile) {
          let matched: boolean | null = null;
          try { matched = await this.deps.reconcile(sending); } catch { matched = null; }
          if (matched) {
            this.remove(item.id);
            void this.persist();
            this.emit({ type: 'done', item: sending });
            this.settle(item.id, { state: 'done', item: sending, reconciled: true });
            return;
          }
          if (matched === null) {
            // could not read the ticket: treat like a network problem and try again later
            const attempts = item.attempts + 1;
            const next: QueueItem = {
              ...sending, status: 'pending', attempts, lastError: error,
              nextAttemptAt: this.now() + backoffDelay(attempts - 1, { random: this.deps.random }),
            };
            this.replace(next);
            void this.persist();
            this.settle(item.id, { state: 'queued', item: next });
            return;
          }
        }
        this.fail(sending, error, item.attempts + 1);
        return;
      }
      default:
        this.fail(sending, error, item.attempts + 1);
        return;
    }
  }

  private fail(item: QueueItem, error: QueueError, attempts: number): void {
    const failed: QueueItem = { ...item, status: 'failed', attempts, lastError: error };
    // A first, foreground attempt that fails for good is reported to the caller and not queued
    const foreground = this.waiters.has(item.id) && item.attempts === 0;
    if (foreground) {
      this.remove(item.id);
    } else {
      this.replace(failed);
      this.emit({ type: 'failed', item: failed });
    }
    void this.persist();
    this.settle(item.id, { state: 'failed', item: failed, error });
  }
}

// ── Default store implementations ─────────────────────────

export function memoryStore(): QueueStore {
  const data = new Map<string, QueueItem[]>();
  return {
    async load(userId) { return (data.get(userId) ?? []).slice(); },
    async save(userId, items) { data.set(userId, items.slice()); },
  };
}

const LS_PREFIX = 'smms_offline_queue_v1:';

/** JSON in localStorage. Items that carry a Blob are not persisted here (they stay in memory). */
export function localStorageStore(storage: Storage): QueueStore {
  return {
    async load(userId) {
      try {
        const raw = storage.getItem(LS_PREFIX + userId);
        const parsed: unknown = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? (parsed as QueueItem[]) : [];
      } catch {
        return [];
      }
    },
    async save(userId, items) {
      try {
        const storable = items.filter((i) => !i.file);
        if (storable.length === 0) storage.removeItem(LS_PREFIX + userId);
        else storage.setItem(LS_PREFIX + userId, JSON.stringify(storable));
      } catch { /* quota or blocked storage */ }
    },
  };
}

const IDB_NAME = 'smms-offline';
const IDB_STORE = 'queues';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(IDB_STORE)) req.result.createObjectStore(IDB_STORE);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new Error('indexedDB blocked'));
  });
}

/** IndexedDB keeps photo Blobs. Falls back to `fallback` for every call if IndexedDB fails. */
export function indexedDbStore(fallback: QueueStore): QueueStore {
  let db: Promise<IDBDatabase> | null = null;
  const getDb = () => (db ??= openDb());
  return {
    async load(userId) {
      try {
        const d = await getDb();
        const value = await new Promise<unknown>((resolve, reject) => {
          const r = d.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).get(userId);
          r.onsuccess = () => resolve(r.result);
          r.onerror = () => reject(r.error);
        });
        return Array.isArray(value) ? (value as QueueItem[]) : [];
      } catch {
        return fallback.load(userId);
      }
    },
    async save(userId, items) {
      try {
        const d = await getDb();
        await new Promise<void>((resolve, reject) => {
          const tx = d.transaction(IDB_STORE, 'readwrite');
          if (items.length === 0) tx.objectStore(IDB_STORE).delete(userId);
          else tx.objectStore(IDB_STORE).put(items, userId);
          tx.oncomplete = () => resolve();
          tx.onerror = () => reject(tx.error);
          tx.onabort = () => reject(tx.error);
        });
      } catch {
        await fallback.save(userId, items);
      }
    },
  };
}

export function createDefaultStore(): QueueStore {
  let ls: Storage | null = null;
  try { ls = typeof window !== 'undefined' ? window.localStorage : null; } catch { ls = null; }
  const base = ls ? localStorageStore(ls) : memoryStore();
  return typeof indexedDB !== 'undefined' ? indexedDbStore(base) : base;
}
