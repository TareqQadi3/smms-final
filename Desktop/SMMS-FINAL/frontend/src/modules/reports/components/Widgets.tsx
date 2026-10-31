'use client';

import { useState, type ReactNode } from 'react';
import { Download } from 'lucide-react';
import { ApiErrorView } from '@/components/assets/ApiErrorView';
import { EmptyState, LoadingState } from '@/components/common';
import { Button } from '@/components/ui/button';
import { useModuleT } from '@/lib/module-i18n';
import type { LoadState } from '@/modules/assets/lib';
import { csvError, downloadCsv } from '../csv';
import { reportsDict } from '../i18n';

/** Number with Latin digits (matches the rest of the UI); null is a dash. */
export function fmt(n: number | string | null | undefined, digits = 2): string {
  if (n === null || n === undefined || n === '') return '-';
  const v = Number(n);
  if (Number.isNaN(v)) return String(n);
  return v.toLocaleString('en-US', { maximumFractionDigits: digits });
}

export interface BarItem {
  label: string;
  value: number;
  /** Text shown next to the bar (defaults to the value). */
  display?: string;
}

/**
 * Horizontal bar chart built from plain markup: the list itself is the accessible content (label and
 * value are real text), bars are decorative. Logical CSS only, so it mirrors in RTL.
 */
export function BarChart({ title, items, max }: { title: string; items: BarItem[]; max?: number }) {
  const top = max ?? Math.max(1, ...items.map((i) => i.value));
  return (
    <figure className="space-y-2">
      <figcaption className="text-sm font-semibold">{title}</figcaption>
      <ul className="space-y-1.5" aria-label={title}>
        {items.map((i) => (
          <li key={i.label} className="grid grid-cols-[minmax(5rem,10rem)_1fr_auto] items-center gap-2 text-sm">
            <span className="truncate" title={i.label}>{i.label}</span>
            <span aria-hidden="true" className="h-3 overflow-hidden rounded-full bg-muted">
              <span className="block h-full rounded-full bg-primary" style={{ inlineSize: `${Math.max(0, Math.min(100, (i.value / top) * 100))}%` }} />
            </span>
            <span className="tabular-nums text-muted-foreground">{i.display ?? fmt(i.value)}</span>
          </li>
        ))}
      </ul>
    </figure>
  );
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-lg border p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-xl font-bold tabular-nums">{value}</p>
    </div>
  );
}

/** Shared loading / error / empty handling of one report request. */
export function ReportState<T>({
  state,
  isEmpty,
  children,
}: {
  state: LoadState<T>;
  isEmpty?: (d: T) => boolean;
  children: (d: T) => ReactNode;
}) {
  const { t } = useModuleT(reportsDict);
  return (
    <div aria-live="polite" aria-busy={state.loading}>
      {state.loading && <LoadingState label={t('loading')} />}
      {!state.loading && state.error && <ApiErrorView error={state.error} onRetry={state.reload} />}
      {!state.loading && !state.error && state.data !== null && (isEmpty?.(state.data) ? <EmptyState title={t('noData')} /> : children(state.data))}
    </div>
  );
}

/** CSV download through an authenticated request (see csv.ts); shows busy, success and error states. */
export function CsvButton({ path, params, fallbackName, label }: { path: string; params: Record<string, unknown>; fallbackName: string; label?: string }) {
  const { t } = useModuleT(reportsDict);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const click = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const name = await downloadCsv(path, params, fallbackName);
      setMsg({ kind: 'ok', text: t('csvOk', { name }) });
    } catch (err) {
      const e = await csvError(err);
      setMsg({ kind: 'err', text: e.message || t('csvFailed') });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button type="button" variant="outline" size="sm" disabled={busy} onClick={click}>
        <Download aria-hidden="true" />
        {busy ? t('csvBusy') : (label ?? t('csv'))}
      </Button>
      <span aria-live="polite">
        {msg && <span role={msg.kind === 'err' ? 'alert' : 'status'} className={msg.kind === 'err' ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'}>{msg.text}</span>}
      </span>
    </div>
  );
}
