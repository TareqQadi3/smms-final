'use client';

import Link from 'next/link';
import { useCallback, useState, type FormEvent } from 'react';
import { ApiErrorView } from '@/components/assets/ApiErrorView';
import { SELECT_CLASS } from '@/components/assets/styles';
import { EmptyState, FormField, LoadingState, Pagination, PermissionDenied } from '@/components/common';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { useCenters } from '@/modules/assets/hooks';
import { useLoad } from '@/modules/assets/lib';
import { hasRole, PM_READ, PM_WRITE } from '@/modules/assets/roles';
import { pmApi } from '../api';
import { pmDict } from '../i18n';

type ActiveFilter = '' | 'true' | 'false';

export function PlanListView() {
  const { t, lang } = useModuleT(pmDict);
  const { t: te } = useModuleT(enumDict);
  const role = useAuthStore((s) => s.user?.role);
  const centers = useCenters();
  const [draft, setDraft] = useState<{ centerId: string; active: ActiveFilter; overdue: boolean }>({ centerId: '', active: '', overdue: false });
  const [applied, setApplied] = useState(draft);
  const [page, setPage] = useState(0);
  const allowed = hasRole(role, PM_READ);

  const fetcher = useCallback(
    () =>
      pmApi.list({
        centerId: applied.centerId,
        active: applied.active === '' ? '' : applied.active === 'true',
        overdue: applied.overdue || undefined,
        page,
        size: 20,
      }),
    [applied, page]
  );
  const { data, error, loading, reload } = useLoad(fetcher, allowed);
  if (!allowed) return <PermissionDenied />;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setApplied(draft);
  };
  const centerList = centers.data ?? [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t('title')}</h1>
          {data && <p className="text-sm text-muted-foreground">{t('subtitle', { n: data.totalElements })}</p>}
        </div>
        {hasRole(role, PM_WRITE) && (
          <Link
            href="/maintenance-plans/new"
            className="inline-flex h-8 max-md:min-h-10 items-center rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            {t('add')}
          </Link>
        )}
      </div>

      <form onSubmit={submit} role="search" aria-label={t('list')} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2 lg:grid-cols-4">
        {centerList.length !== 1 && (
          <FormField label={t('center')}>
            <select className={SELECT_CLASS} value={draft.centerId} onChange={(e) => setDraft({ ...draft, centerId: e.target.value })}>
              <option value="">{t('allCenters')}</option>
              {centerList.map((c) => <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>)}
            </select>
          </FormField>
        )}
        <FormField label={t('activeFilter')}>
          <select className={SELECT_CLASS} value={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.value as ActiveFilter })}>
            <option value="">{t('allPlans')}</option>
            <option value="true">{t('activeOnly')}</option>
            <option value="false">{t('inactiveOnly')}</option>
          </select>
        </FormField>
        <label className="flex min-h-10 items-center gap-2 self-end text-sm">
          <input type="checkbox" className="size-4" checked={draft.overdue} onChange={(e) => setDraft({ ...draft, overdue: e.target.checked })} />
          {t('overdueOnly')}
        </label>
        <div className="flex items-end">
          <Button type="submit" className="w-full">{t('filter')}</Button>
        </div>
      </form>

      <div aria-live="polite">
        {loading && <LoadingState />}
        {!loading && error && <ApiErrorView error={error} onRetry={reload} />}
        {!loading && !error && data && data.content.length === 0 && <EmptyState title={t('emptyTitle')} description={t('emptyBody')} />}
      </div>

      {!loading && !error && data && data.content.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">{t('list')}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t('planTitle')}</TableHead>
                  <TableHead scope="col">{t('asset')}</TableHead>
                  <TableHead scope="col">{t('frequency')}</TableHead>
                  <TableHead scope="col">{t('nextDue')}</TableHead>
                  <TableHead scope="col">{t('activeFilter')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((p) => (
                  <TableRow key={p.id}>
                    <TableCell>
                      <Link href={`/maintenance-plans/${p.id}`} className="font-medium text-primary underline-offset-4 hover:underline">{p.title}</Link>
                    </TableCell>
                    <TableCell>
                      <Link href={`/assets/${p.assetId}`} className="hover:underline">
                        {p.assetCode ? `${p.assetCode} - ` : ''}{p.assetName}
                      </Link>
                    </TableCell>
                    <TableCell>{t('everyN', { n: p.frequencyInterval, unit: enumLabel(te, 'freq', p.frequencyUnit) })}</TableCell>
                    <TableCell>{formatDate(p.nextDueDate, lang)}</TableCell>
                    <TableCell className="space-x-1 space-x-reverse">
                      <Badge variant={p.active ? 'secondary' : 'outline'}>{p.active ? t('active') : t('inactive')}</Badge>
                      {p.overdue && <Badge variant="destructive">{t('overdue')}</Badge>}
                    </TableCell>
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
