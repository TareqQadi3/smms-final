'use client';

import { useCallback, useEffect, useRef, useState, type ReactNode, type SelectHTMLAttributes } from 'react';
import { EmptyState, ErrorState, LoadingState, PermissionDenied } from '@/components/common';
import { useAuthStore } from '@/store/authStore';
import { cn } from '@/lib/utils';
import { mapApiError, type MappedError } from './errors';

export const selectClass =
  'h-9 w-full min-w-0 rounded-lg border border-input bg-background px-2.5 text-sm outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive max-md:h-10';

/** Native select styled like Input (works inside FormField, keyboard and screen-reader friendly). */
export function NativeSelect({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn(selectClass, className)} {...props}>
      {children}
    </select>
  );
}

/** Trims trailing zeros of a decimal quantity (3 decimals max). */
export function fmtQty(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '-';
  const v = Number(n);
  if (!Number.isFinite(v)) return '-';
  return String(Number(v.toFixed(3)));
}

export const fmtMoney = (n: number | string | null | undefined) =>
  n === null || n === undefined ? '-' : Number(n).toFixed(2);

interface Named {
  name?: string;
  nameAr?: string;
  fullName?: string;
  fullNameAr?: string;
}
/** Arabic name when the UI is Arabic and one exists, otherwise the English name. */
export function localName(x: Named | null | undefined, isAr: boolean): string {
  if (!x) return '-';
  const ar = x.nameAr ?? x.fullNameAr;
  const en = x.name ?? x.fullName;
  return (isAr ? ar || en : en || ar) ?? '-';
}

export interface ApiData<T> {
  data: T | null;
  loading: boolean;
  error: MappedError | null;
  reload: () => void;
}

/** Fetch-on-mount/deps hook with race protection. Errors are mapped to localized messages. */
export function useApiData<T>(fetcher: () => Promise<T>, deps: unknown[], enabled = true): ApiData<T> {
  const lang = useAuthStore((s) => s.lang);
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<MappedError | null>(null);
  const [tick, setTick] = useState(0);
  const seq = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    fetcherRef
      .current()
      .then((d) => {
        if (mine === seq.current) setData(d);
      })
      .catch((e) => {
        if (mine === seq.current) setError(mapApiError(e, lang));
      })
      .finally(() => {
        if (mine === seq.current) setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled, lang]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, loading, error, reload };
}

/**
 * Standard loading / permission / error / empty switch around a data region.
 * `empty` true shows EmptyState instead of children.
 */
export function DataState({
  loading,
  error,
  onRetry,
  empty,
  emptyTitle,
  emptyDescription,
  emptyAction,
  children,
}: {
  loading: boolean;
  error: MappedError | null;
  onRetry?: () => void;
  empty?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  children: ReactNode;
}) {
  if (loading) return <LoadingState rows={4} />;
  if (error?.kind === 'forbidden') return <PermissionDenied />;
  if (error) return <ErrorState message={error.message} onRetry={onRetry} />;
  if (empty) return <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />;
  return <>{children}</>;
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-bold text-foreground">{title}</h1>
        {subtitle && <p className="text-sm text-muted-foreground">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Inline result banner: role=alert for errors, role=status for success. */
export function Banner({ kind, children }: { kind: 'error' | 'success' | 'info'; children: ReactNode }) {
  const styles = {
    error: 'border-destructive/40 bg-destructive/10 text-destructive',
    success: 'border-emerald-300 bg-emerald-50 text-emerald-900 dark:bg-emerald-950/30 dark:text-emerald-200',
    info: 'border-border bg-muted text-foreground',
  }[kind];
  return (
    <div role={kind === 'error' ? 'alert' : 'status'} className={cn('rounded-md border px-3 py-2 text-sm', styles)}>
      {children}
    </div>
  );
}

/** Validates a decimal quantity the way the backend does (> 0, max 3 decimals, <= 1,000,000). */
export function parseQty(raw: string, max = 1_000_000): { value?: number; error?: 'required' | 'positive' | 'decimals' | 'max' } {
  const s = raw.trim().replace(',', '.');
  if (s === '') return { error: 'required' };
  if (!/^\d*\.?\d+$|^\d+\.$/.test(s)) return { error: 'positive' };
  const v = Number(s);
  if (!Number.isFinite(v) || v <= 0) return { error: 'positive' };
  if (/\.\d{4,}$/.test(s)) return { error: 'decimals' };
  if (v > max) return { error: 'max' };
  return { value: v };
}

export interface CenterOption {
  id: string;
  name: string;
  nameAr?: string;
}

/** Centers the user can pick: own center, or all centers for the system administrator. */
export function useCenterOptions(): { centers: CenterOption[]; loading: boolean } {
  const user = useAuthStore((s) => s.user);
  const [centers, setCenters] = useState<CenterOption[]>(user?.center ? [user.center] : []);
  const [loading, setLoading] = useState(false);
  const role = user?.role;
  const ownCenter = user?.center;
  useEffect(() => {
    if (role !== 'system_admin') {
      setCenters(ownCenter ? [ownCenter] : []);
      return;
    }
    let live = true;
    setLoading(true);
    import('@/lib/api')
      .then(({ centerApi }) => centerApi.getAll())
      .then((r) => {
        if (live) setCenters(Array.isArray(r.data) ? r.data : (r.data?.content ?? []));
      })
      .catch(() => {})
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [role, ownCenter]);
  return { centers, loading };
}

/** Active warehouses of the user's scope (only for roles that may list them). */
export function useWarehouseOptions(enabled = true) {
  return useApiData(() => import('./api').then((m) => m.inventoryApi.listWarehouses({ active: true, size: 100, sort: 'name,asc' })), [], enabled);
}

/** Active items of one center (catalog is per center), at most 200. */
export function useItemOptions(centerId: string | undefined) {
  return useApiData(
    () => import('./api').then((m) => m.inventoryApi.listItems({ centerId, active: true, size: 200, sort: 'name,asc' })),
    [centerId],
    !!centerId
  );
}
