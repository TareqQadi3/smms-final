/**
 * Small per-user read cache (localStorage) so the queue and the last opened tickets still show on a
 * bad connection. Everything is wrapped: private mode or a full quota just means no cache.
 */
const PREFIX = 'smms_tech_cache_v1:';
const MAX_ENTRIES = 40;

interface Stored { order: string[]; items: Record<string, { at: number; v: unknown }> }

function read(userId: string): Stored {
  try {
    const raw = window.localStorage.getItem(PREFIX + userId);
    const parsed = raw ? (JSON.parse(raw) as Stored) : null;
    if (parsed && Array.isArray(parsed.order) && parsed.items) return parsed;
  } catch { /* ignore */ }
  return { order: [], items: {} };
}

export function cacheGet<T>(userId: string, key: string): { at: number; value: T } | null {
  if (typeof window === 'undefined') return null;
  const e = read(userId).items[key];
  return e ? { at: e.at, value: e.v as T } : null;
}

export function cachePut(userId: string, key: string, value: unknown, now = Date.now()): void {
  if (typeof window === 'undefined') return;
  try {
    const s = read(userId);
    s.order = [key, ...s.order.filter((k) => k !== key)];
    s.items[key] = { at: now, v: value };
    for (const old of s.order.slice(MAX_ENTRIES)) delete s.items[old];
    s.order = s.order.slice(0, MAX_ENTRIES);
    window.localStorage.setItem(PREFIX + userId, JSON.stringify(s));
  } catch { /* quota or blocked storage: no cache */ }
}

export function cacheClear(userId: string): void {
  try { window.localStorage.removeItem(PREFIX + userId); } catch { /* ignore */ }
}
