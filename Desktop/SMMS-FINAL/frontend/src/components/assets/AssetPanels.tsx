'use client';

import Link from 'next/link';
import { useCallback, useState, type FormEvent } from 'react';
import { EmptyState, FormField, LoadingState, Pagination, PermissionDenied } from '@/components/common';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { assetsApi } from '@/modules/assets/api';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { assetsDict } from '@/modules/assets/i18n';
import { apiError, useLoad } from '@/modules/assets/lib';
import { ApiErrorView } from './ApiErrorView';

export function HistoryPanel({ assetId }: { assetId: string }) {
  const { t, lang } = useModuleT(assetsDict);
  const { t: te } = useModuleT(enumDict);
  const [page, setPage] = useState(0);
  const fetcher = useCallback(() => assetsApi.workOrders(assetId, page), [assetId, page]);
  const { data, error, loading, reload } = useLoad(fetcher);

  if (loading) return <LoadingState />;
  if (error) return <ApiErrorView error={error} onRetry={reload} />;
  if (!data || data.content.length === 0) return <EmptyState title={t('noHistory')} />;
  return (
    <div className="space-y-3">
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <caption className="sr-only">{t('history')}</caption>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">{t('ticket')}</TableHead>
              <TableHead scope="col">{t('titleCol')}</TableHead>
              <TableHead scope="col">{t('status')}</TableHead>
              <TableHead scope="col">{t('priority')}</TableHead>
              <TableHead scope="col">{t('assignee')}</TableHead>
              <TableHead scope="col">{t('created')}</TableHead>
              <TableHead scope="col">{t('completed')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.content.map((w) => (
              <TableRow key={w.id}>
                <TableCell>
                  <Link href={`/work-orders/${w.id}`} className="font-mono text-xs text-primary underline-offset-4 hover:underline">
                    {w.ticketNumber}
                  </Link>
                </TableCell>
                <TableCell>
                  {w.title}
                  {w.preventive && <Badge variant="outline" className="ms-2">{t('preventive')}</Badge>}
                  {w.slaBreached && <Badge variant="destructive" className="ms-2">{t('slaBreached')}</Badge>}
                </TableCell>
                <TableCell>{enumLabel(te, 'woStatus', w.status)}</TableCell>
                <TableCell>{enumLabel(te, 'priority', w.priority)}</TableCell>
                <TableCell>{w.assignedToName ?? '-'}</TableCell>
                <TableCell>{formatDate(w.createdAt, lang)}</TableCell>
                <TableCell>{formatDate(w.completedDate, lang)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
    </div>
  );
}

export function MaterialsPanel({ assetId }: { assetId: string }) {
  const { t, lang } = useModuleT(assetsDict);
  const [draft, setDraft] = useState({ from: '', to: '' });
  const [range, setRange] = useState({ from: '', to: '' });
  const [rangeError, setRangeError] = useState('');
  const fetcher = useCallback(() => assetsApi.materials(assetId, range.from, range.to), [assetId, range]);
  const { data, error, loading, reload } = useLoad(fetcher);

  const apply = (e: FormEvent) => {
    e.preventDefault();
    if (draft.from && draft.to && draft.to < draft.from) {
      setRangeError(lang === 'ar' ? 'تاريخ النهاية قبل البداية' : 'The end date is before the start date');
      return;
    }
    setRangeError('');
    setRange(draft);
  };

  return (
    <div className="space-y-3">
      <form onSubmit={apply} className="flex flex-wrap items-end gap-3">
        <FormField label={t('from')}>
          <Input type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </FormField>
        <FormField label={t('to')} error={rangeError}>
          <Input type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </FormField>
        <Button type="submit" variant="outline">{t('filter')}</Button>
      </form>
      {loading && <LoadingState />}
      {!loading && error?.status === 403 && <PermissionDenied description={t('materialsDenied')} />}
      {!loading && error && error.status !== 403 && <ApiErrorView error={error} onRetry={reload} />}
      {!loading && !error && data && data.length === 0 && <EmptyState title={t('noMaterials')} />}
      {!loading && !error && data && data.length > 0 && (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <caption className="sr-only">{t('materials')}</caption>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{t('item')}</TableHead>
                <TableHead scope="col">{t('sku')}</TableHead>
                <TableHead scope="col">{t('quantity')}</TableHead>
                <TableHead scope="col">{t('kind')}</TableHead>
                <TableHead scope="col">{t('date')}</TableHead>
                <TableHead scope="col">{t('ticket')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((m) => (
                <TableRow key={m.movementId}>
                  <TableCell>{lang === 'ar' ? m.itemNameAr || m.itemName : m.itemName}</TableCell>
                  <TableCell className="font-mono text-xs">{m.sku ?? '-'}</TableCell>
                  <TableCell>{String(m.quantity)}</TableCell>
                  <TableCell>{m.kind}</TableCell>
                  <TableCell>{formatDate(m.createdAt, lang, true)}</TableCell>
                  <TableCell>
                    <Link href={`/work-orders/${m.workOrderId}`} className="text-primary underline-offset-4 hover:underline">
                      {t('ticket')}
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

export function DowntimePanel({ assetId, canWrite, onChanged }: { assetId: string; canWrite: boolean; onChanged: () => void }) {
  const { t, lang } = useModuleT(assetsDict);
  const [page, setPage] = useState(0);
  const fetcher = useCallback(() => assetsApi.downtime(assetId, page), [assetId, page]);
  const { data, error, loading, reload } = useLoad(fetcher);
  const [reason, setReason] = useState('');
  const [workOrderId, setWorkOrderId] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const duration = (s: number | null) => {
    if (s === null) return t('ongoing');
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return h > 0 ? `${t('hours', { n: h })} ${t('minutes', { n: m })}` : t('minutes', { n: m });
  };

  const start = async (e: FormEvent) => {
    e.preventDefault();
    if (!reason.trim()) {
      setReasonError(t('vReasonRequired'));
      return;
    }
    setReasonError('');
    setBusy(true);
    setMsg(null);
    try {
      await assetsApi.startDowntime(assetId, { reason: reason.trim(), ...(workOrderId.trim() ? { workOrderId: workOrderId.trim() } : {}) });
      setReason('');
      setWorkOrderId('');
      setMsg({ kind: 'ok', text: t('downtimeStarted') });
      reload();
      onChanged();
    } catch (err) {
      setMsg({ kind: 'err', text: apiError(err).message || t('saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  const end = async (dtId: string) => {
    setBusy(true);
    setMsg(null);
    try {
      await assetsApi.endDowntime(assetId, dtId);
      setMsg({ kind: 'ok', text: t('downtimeEnded') });
      reload();
      onChanged();
    } catch (err) {
      setMsg({ kind: 'err', text: apiError(err).message || t('saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      {canWrite && (
        <form onSubmit={start} noValidate className="grid gap-3 rounded-lg border p-3 sm:grid-cols-3" aria-label={t('startDowntime')}>
          <FormField label={t('downtimeReason')} required error={reasonError} className="sm:col-span-2">
            <Textarea rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} disabled={busy} />
          </FormField>
          <FormField label={t('workOrderId')}>
            <Input value={workOrderId} onChange={(e) => setWorkOrderId(e.target.value)} disabled={busy} />
          </FormField>
          <div>
            <Button type="submit" disabled={busy}>{t('startDowntime')}</Button>
          </div>
        </form>
      )}
      <div aria-live="polite">
        {msg && (
          <p role={msg.kind === 'err' ? 'alert' : 'status'} className={msg.kind === 'err' ? 'text-sm text-destructive' : 'text-sm text-green-700 dark:text-green-500'}>
            {msg.text}
          </p>
        )}
      </div>
      {loading && <LoadingState />}
      {!loading && error && <ApiErrorView error={error} onRetry={reload} />}
      {!loading && !error && data && data.content.length === 0 && <EmptyState title={t('noDowntime')} />}
      {!loading && !error && data && data.content.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">{t('downtime')}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t('startedAt')}</TableHead>
                  <TableHead scope="col">{t('endedAt')}</TableHead>
                  <TableHead scope="col">{t('duration')}</TableHead>
                  <TableHead scope="col">{t('reason')}</TableHead>
                  {canWrite && <TableHead scope="col"><span className="sr-only">{t('endDowntime')}</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((d) => (
                  <TableRow key={d.id}>
                    <TableCell>{formatDate(d.startedAt, lang, true)}</TableCell>
                    <TableCell>{d.endedAt ? formatDate(d.endedAt, lang, true) : <Badge variant="destructive">{t('ongoing')}</Badge>}</TableCell>
                    <TableCell>{duration(d.durationSeconds)}</TableCell>
                    <TableCell className="max-w-xs whitespace-normal">{d.reason}</TableCell>
                    {canWrite && (
                      <TableCell>
                        {!d.endedAt && (
                          <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => end(d.id)}>
                            {t('endDowntime')}
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
