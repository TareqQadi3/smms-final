'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { EmptyState, ErrorState, LoadingState, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict, type WorkflowKey } from '@/modules/workflow/i18n';
import { adminNotificationApi, type DeliveryQuery } from '@/modules/workflow/api';
import { describeError } from '@/modules/workflow/errors';
import type { DeliveryStatus } from '@/modules/workflow/types';
import { useLoad } from './useLoad';

const STATUSES: DeliveryStatus[] = ['pending', 'failed', 'sent', 'dead', 'not_configured', 'skipped_no_address'];
const SEL = 'h-9 rounded-md border border-input bg-background px-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring';
const PAGE_SIZE = 20;

const statusTone: Record<string, string> = {
  sent: 'bg-green-100 text-green-800', pending: 'bg-blue-100 text-blue-800', failed: 'bg-amber-100 text-amber-800',
  dead: 'bg-red-100 text-red-800', not_configured: 'bg-slate-200 text-slate-800', skipped_no_address: 'bg-slate-100 text-slate-700',
};

/** Provider status (no secrets) for system admins. */
export function ProviderStatusCard() {
  const { t } = useModuleT(workflowDict);
  const list = useLoad(async () => (await adminNotificationApi.providers()).data, 'providers');
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{t('providers')}</CardTitle></CardHeader>
      <CardContent>
        {list.loading ? <LoadingState rows={1} /> : list.error ? (
          <ErrorState message={describeError(list.error, t)} onRetry={list.reload} />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {(list.data ?? []).map((p) => (
              <li key={p.channel} className="space-y-1 rounded-md border p-3 text-sm">
                <p className="font-medium">{p.channel === 'email' ? t('channelEmail') : t('channelSms')} <span className="text-xs text-muted-foreground">({p.provider})</span></p>
                <p>
                  <span className={p.enabled ? 'text-green-700' : 'text-slate-600'}>{p.enabled ? t('providerEnabled') : t('providerDisabled')}</span>
                  {' / '}
                  <span className={p.configured ? 'text-green-700' : 'text-amber-700'}>{p.configured ? t('providerConfigured') : t('providerNotConfigured')}</span>
                </p>
                {p.problems.length > 0 && (
                  <div className="text-xs text-amber-700">
                    <p className="font-medium">{t('providerProblems')}</p>
                    <ul className="list-disc ps-4">{p.problems.map((x) => <li key={x}>{x}</li>)}</ul>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

/** Delivery log with status/channel/date filters and zero-based pagination (system admin only). */
export function DeliveriesTable() {
  const { t, lang } = useModuleT(workflowDict);
  const [draft, setDraft] = useState({ status: '', channel: '', from: '', to: '' });
  const [applied, setApplied] = useState<DeliveryQuery>({});
  const [page, setPage] = useState(0);
  const [rangeError, setRangeError] = useState('');

  const list = useLoad(
    async () => (await adminNotificationApi.deliveries({ ...applied, page, size: PAGE_SIZE })).data,
    JSON.stringify([applied, page])
  );

  const apply = (e: React.FormEvent) => {
    e.preventDefault();
    if (draft.from && draft.to && draft.from > draft.to) { setRangeError(t('invalidRange')); return; }
    setRangeError('');
    setPage(0);
    setApplied({ status: draft.status, channel: draft.channel, from: draft.from, to: draft.to });
  };
  const reset = () => { setDraft({ status: '', channel: '', from: '', to: '' }); setRangeError(''); setPage(0); setApplied({}); };

  const rows = list.data?.content ?? [];
  const statusText = (s: string) => t(`status_${s}` as WorkflowKey);

  return (
    <Card>
      <CardContent className="space-y-4 pt-4">
        <form onSubmit={apply} className="flex flex-wrap items-end gap-3" aria-label={t('applyFilters')}>
          <div className="space-y-1">
            <Label htmlFor="f-status">{t('filterStatus')}</Label>
            <select id="f-status" className={SEL} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>
              <option value="">{t('filterAll')}</option>
              {STATUSES.map((s) => <option key={s} value={s}>{statusText(s)}</option>)}
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="f-channel">{t('filterChannel')}</Label>
            <select id="f-channel" className={SEL} value={draft.channel} onChange={(e) => setDraft({ ...draft, channel: e.target.value })}>
              <option value="">{t('filterAll')}</option>
              <option value="email">{t('channelEmail')}</option>
              <option value="sms">{t('channelSms')}</option>
            </select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="f-from">{t('filterFrom')}</Label>
            <Input id="f-from" type="date" className="h-9" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="f-to">{t('filterTo')}</Label>
            <Input id="f-to" type="date" className="h-9" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          </div>
          <Button type="submit" size="sm">{t('applyFilters')}</Button>
          <Button type="button" size="sm" variant="outline" onClick={reset}>{t('resetFilters')}</Button>
        </form>
        {rangeError && <p role="alert" className="text-sm text-destructive">{rangeError}</p>}

        {list.loading ? (
          <LoadingState rows={4} />
        ) : list.error ? (
          <ErrorState message={describeError(list.error, t)} onRetry={list.reload} />
        ) : rows.length === 0 ? (
          <EmptyState title={t('noDeliveries')} />
        ) : (
          <>
            <p className="text-xs text-muted-foreground" aria-live="polite">{t('totalRows', { n: list.data?.totalElements ?? 0 })}</p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="border-b bg-muted/50">
                  <tr>
                    {(['colChannel', 'colDestination', 'colStatus', 'colAttempts', 'colProvider', 'colCreated', 'colSent', 'colError'] as WorkflowKey[]).map((k) => (
                      <th key={k} scope="col" className="px-3 py-2 text-start font-medium text-muted-foreground">{t(k)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="px-3 py-2">{r.channel === 'email' ? t('channelEmail') : t('channelSms')}</td>
                      <td className="px-3 py-2"><bdi dir="ltr" className="font-mono text-xs">{r.destinationMasked || '-'}</bdi></td>
                      <td className="px-3 py-2">
                        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusTone[r.status] ?? 'bg-gray-100'}`}>{statusText(r.status)}</span>
                      </td>
                      <td className="px-3 py-2 tabular-nums">{r.attempts}</td>
                      <td className="px-3 py-2">{r.provider || '-'}</td>
                      <td className="whitespace-nowrap px-3 py-2 text-xs tabular-nums">{formatDate(r.createdAt, lang, true)}</td>
                      <td className="whitespace-nowrap px-3 py-2 text-xs tabular-nums">{formatDate(r.sentAt, lang, true)}</td>
                      <td className="max-w-[240px] truncate px-3 py-2 text-xs text-muted-foreground" title={r.lastError ?? undefined}>{r.lastError || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={list.data?.page ?? page} totalPages={list.data?.totalPages ?? 0} onPageChange={setPage} />
          </>
        )}
      </CardContent>
    </Card>
  );
}
