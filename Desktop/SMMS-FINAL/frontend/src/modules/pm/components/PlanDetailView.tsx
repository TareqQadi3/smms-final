'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useState } from 'react';
import { ApiErrorView } from '@/components/assets/ApiErrorView';
import { ConfirmDialog, EmptyState, LoadingState, Pagination, PermissionDenied } from '@/components/common';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { apiError, useLoad } from '@/modules/assets/lib';
import { hasRole, PM_READ, PM_WRITE } from '@/modules/assets/roles';
import { pmApi } from '../api';
import { pmDict } from '../i18n';
import type { PlanRun } from '../types';
import { PlanForm } from './PlanForm';

type Action = 'activate' | 'deactivate' | 'generate' | 'delete';

function RunsTable({ runs, caption }: { runs: PlanRun[]; caption: string }) {
  const { t, lang } = useModuleT(pmDict);
  const { t: te } = useModuleT(enumDict);
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <caption className="sr-only">{caption}</caption>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">{t('dueDate')}</TableHead>
            <TableHead scope="col">{t('runStatus')}</TableHead>
            <TableHead scope="col">{t('source')}</TableHead>
            <TableHead scope="col">{t('workOrder')}</TableHead>
            <TableHead scope="col">{t('error')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {runs.map((r) => (
            <TableRow key={r.id}>
              <TableCell>{formatDate(r.dueDate, lang)}</TableCell>
              <TableCell>
                <Badge variant={r.status === 'failed' ? 'destructive' : 'secondary'}>{enumLabel(te, 'runStatus', r.status)}</Badge>
              </TableCell>
              <TableCell>{r.source}</TableCell>
              <TableCell>
                {r.workOrderId ? (
                  <Link href={`/work-orders/${r.workOrderId}`} className="text-primary underline-offset-4 hover:underline">{t('openWorkOrder')}</Link>
                ) : '-'}
              </TableCell>
              <TableCell className="max-w-xs whitespace-normal text-destructive">{r.error ?? ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

export function PlanDetailView({ id }: { id: string }) {
  const { t, lang } = useModuleT(pmDict);
  const { t: te } = useModuleT(enumDict);
  const router = useRouter();
  const role = useAuthStore((s) => s.user?.role);
  const allowed = hasRole(role, PM_READ);
  const fetcher = useCallback(() => pmApi.get(id), [id]);
  const { data: plan, error, loading, reload } = useLoad(fetcher, allowed);
  const [runsPage, setRunsPage] = useState(0);
  const runsFetcher = useCallback(() => pmApi.runs(id, runsPage), [id, runsPage]);
  const runs = useLoad(runsFetcher, allowed);
  const [editing, setEditing] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string; woId?: string } | null>(null);

  if (!allowed) return <PermissionDenied />;
  if (loading && !plan) return <LoadingState />;
  if (error && !plan) return <ApiErrorView error={error} onRetry={reload} />;
  if (!plan) return null;
  const writer = hasRole(role, PM_WRITE);

  const run = async () => {
    if (!action) return;
    setBusy(true);
    setMsg(null);
    try {
      if (action === 'activate') {
        await pmApi.activate(id);
        setMsg({ kind: 'ok', text: t('doneActivate') });
      } else if (action === 'deactivate') {
        await pmApi.deactivate(id);
        setMsg({ kind: 'ok', text: t('doneDeactivate') });
      } else if (action === 'generate') {
        const r = await pmApi.generateNow(id);
        setMsg({ kind: 'ok', text: t('doneGenerate'), woId: r.workOrderId });
      } else {
        await pmApi.remove(id);
        router.push('/maintenance-plans');
        return;
      }
      setAction(null);
      reload();
      runs.reload();
    } catch (err) {
      const { status, message } = apiError(err);
      setAction(null);
      setMsg({ kind: 'err', text: status === 409 && action === 'generate' ? message || t('alreadyGenerated') : message || t('saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  const dialog: Record<Action, { title: string; body: string; label: string; destructive?: boolean }> = {
    activate: { title: t('confirmActivate'), body: t('confirmActivateBody'), label: t('activate') },
    deactivate: { title: t('confirmDeactivate'), body: t('confirmDeactivateBody'), label: t('deactivate') },
    generate: { title: t('confirmGenerate'), body: t('confirmGenerateBody'), label: t('generateNow') },
    delete: { title: t('confirmDelete'), body: t('confirmDeleteBody'), label: t('delete'), destructive: true },
  };

  const fields: [string, string][] = [
    [t('asset'), `${plan.assetCode ? `${plan.assetCode} - ` : ''}${plan.assetName}`],
    [t('subType'), enumLabel(te, 'subType', plan.subType)],
    [t('priority'), plan.priority ? enumLabel(te, 'priority', plan.priority) : t('noPriority')],
    [t('frequency'), t('everyN', { n: plan.frequencyInterval, unit: enumLabel(te, 'freq', plan.frequencyUnit) })],
    [t('leadLabel'), t('daysUnit', { n: plan.leadDays })],
    [t('startDateLabel'), formatDate(plan.startDate, lang)],
    [t('nextDue'), formatDate(plan.nextDueDate, lang)],
    [t('owner'), plan.ownerName ?? '-'],
  ];

  return (
    <div className="space-y-5">
      <Link href="/maintenance-plans" className="text-sm text-primary underline-offset-4 hover:underline">{t('back')}</Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-bold">{plan.title}</h1>
          <span className="inline-flex gap-1">
            <Badge variant={plan.active ? 'secondary' : 'outline'}>{plan.active ? t('active') : t('inactive')}</Badge>
            {plan.overdue && <Badge variant="destructive">{t('overdue')}</Badge>}
          </span>
        </div>
        {writer && !editing && (
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={() => setEditing(true)}>{t('edit')}</Button>
            {plan.active ? (
              <Button type="button" variant="outline" onClick={() => setAction('deactivate')}>{t('deactivate')}</Button>
            ) : (
              <Button type="button" variant="outline" onClick={() => setAction('activate')}>{t('activate')}</Button>
            )}
            {plan.active && <Button type="button" onClick={() => setAction('generate')}>{t('generateNow')}</Button>}
            <Button type="button" variant="destructive" onClick={() => setAction('delete')}>{t('delete')}</Button>
          </div>
        )}
      </div>

      <div aria-live="polite">
        {msg && (
          <p role={msg.kind === 'err' ? 'alert' : 'status'} className={msg.kind === 'err' ? 'text-sm text-destructive' : 'text-sm text-green-700 dark:text-green-500'}>
            {msg.text}{' '}
            {msg.woId && <Link href={`/work-orders/${msg.woId}`} className="underline">{t('openWorkOrder')}</Link>}
          </p>
        )}
      </div>

      {editing ? (
        <PlanForm
          mode="edit"
          plan={plan}
          onSaved={() => {
            setEditing(false);
            reload();
          }}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
            {fields.map(([k, v]) => (
              <div key={k}>
                <dt className="text-xs text-muted-foreground">{k}</dt>
                <dd className="text-sm">{v}</dd>
              </div>
            ))}
          </dl>
          {plan.instructions && (
            <section aria-labelledby="plan-instr">
              <h2 id="plan-instr" className="mb-1 text-sm font-semibold">{t('instructions')}</h2>
              <p className="whitespace-pre-wrap text-sm">{plan.instructions}</p>
            </section>
          )}
        </>
      )}

      <section aria-labelledby="plan-runs" className="space-y-3">
        <h2 id="plan-runs" className="text-lg font-semibold">{t('runs')}</h2>
        {runs.loading && <LoadingState />}
        {!runs.loading && runs.error && <ApiErrorView error={runs.error} onRetry={runs.reload} />}
        {!runs.loading && !runs.error && runs.data && runs.data.content.length === 0 && <EmptyState title={t('noRuns')} />}
        {!runs.loading && !runs.error && runs.data && runs.data.content.length > 0 && (
          <>
            <RunsTable runs={runs.data.content} caption={t('runs')} />
            <Pagination page={runs.data.number} totalPages={runs.data.totalPages} onPageChange={setRunsPage} />
          </>
        )}
      </section>

      <ConfirmDialog
        open={action !== null}
        onOpenChange={(o) => !o && setAction(null)}
        title={action ? dialog[action].title : ''}
        description={action ? dialog[action].body : undefined}
        confirmLabel={action ? dialog[action].label : undefined}
        destructive={action ? dialog[action].destructive : false}
        loading={busy}
        onConfirm={run}
      />
    </div>
  );
}
