'use client';

import { useCallback, useEffect, useState } from 'react';

export interface PageData<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApiErr {
  status: number | null;
  /** Server message (English), when there is one. */
  message: string;
}

/** Extracts status and server message from an axios-like error without depending on axios types. */
export function apiError(err: unknown): ApiErr {
  const r = (err as { response?: { status?: number; data?: unknown } } | null)?.response;
  const data = r?.data;
  let message = '';
  if (data && typeof data === 'object' && 'message' in data && typeof (data as { message: unknown }).message === 'string') {
    message = (data as { message: string }).message;
  } else if (typeof data === 'string') {
    message = data;
  }
  return { status: r?.status ?? null, message };
}

/** Drops empty values so they never reach the query string. */
export function clean<T extends Record<string, unknown>>(params: T): Partial<T> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v !== '' && v !== undefined && v !== null) out[k] = v;
  }
  return out as Partial<T>;
}

export interface LoadState<T> {
  data: T | null;
  error: ApiErr | null;
  loading: boolean;
  reload: () => void;
}

/** Runs `fn` whenever its identity changes (wrap it in useCallback); ignores stale answers. */
export function useLoad<T>(fn: () => Promise<T>, enabled = true): LoadState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiErr | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => {
        if (!cancelled) setData(d);
      })
      .catch((e) => {
        if (!cancelled) setError(apiError(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [fn, enabled, tick]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { data, error, loading, reload };
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function addDaysIso(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + days));
  return dt.toISOString().slice(0, 10);
}

/** Inclusive number of days between two ISO dates. */
export function daysBetween(from: string, to: string): number {
  const f = Date.parse(`${from}T00:00:00Z`);
  const t = Date.parse(`${to}T00:00:00Z`);
  return Math.round((t - f) / 86_400_000) + 1;
}
