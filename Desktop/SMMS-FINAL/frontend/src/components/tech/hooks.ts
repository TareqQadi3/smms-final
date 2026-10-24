'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { cacheGet, cachePut } from '@/modules/tech/cache';
import { getQueue } from '@/lib/offline/useOfflineQueue';
import type { QueueEvent } from '@/lib/offline/queue';

export interface Resource<T> {
  data: T | undefined;
  loading: boolean;
  /** The raw failure of the last fetch when there is nothing (not even cached data) to show. */
  error: unknown;
  /** Timestamp of the cached copy when the data on screen is not fresh. */
  staleSince: number | null;
  reload: () => void;
}

/**
 * Stale-while-revalidate loader. With `cacheKey` the last good answer is kept per user and shown
 * immediately (and kept when the network is down), so a slow or missing connection does not blank the screen.
 */
export function useResource<T>(cacheKey: string | null, fetcher: () => Promise<T>, deps: unknown[] = []): Resource<T> {
  const userId = useAuthStore((s) => s.user?.id);
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [dataAt, setDataAt] = useState<number | null>(null);
  const [fresh, setFresh] = useState(false);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    let cancelled = false;
    if (!userId) return;
    if (cacheKey && tick === 0) {
      const hit = cacheGet<T>(userId, cacheKey);
      if (hit) { setData(hit.value); setDataAt(hit.at); setFresh(false); }
    }
    setLoading(true);
    fetcherRef.current()
      .then((v) => {
        if (cancelled) return;
        setData(v);
        setError(null);
        setDataAt(Date.now());
        setFresh(true);
        if (cacheKey) cachePut(userId, cacheKey, v);
      })
      .catch((e) => { if (!cancelled) { setError(e); setFresh(false); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, cacheKey, tick, ...deps]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  // Keep showing cached data when the refresh fails; surface the error only when nothing is shown
  const stale = data !== undefined && !fresh && !loading;
  return { data, loading, error: data === undefined ? error : null, staleSince: stale ? dataAt : null, reload };
}

/** Calls `cb` whenever a queued action finished, was dropped or failed. */
export function useQueueEvents(cb: (e: QueueEvent) => void) {
  const ref = useRef(cb);
  ref.current = cb;
  useEffect(() => getQueue().onEvent((e) => ref.current(e)), []);
}
