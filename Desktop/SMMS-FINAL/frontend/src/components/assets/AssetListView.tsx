'use client';

import Link from 'next/link';
import { useCallback, useState, type FormEvent } from 'react';
import { EmptyState, FormField, LoadingState, Pagination, PermissionDenied } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { assetsApi } from '@/modules/assets/api';
import { ASSET_STATUSES, enumDict, enumLabel } from '@/modules/assets/enums';
import { useCenters } from '@/modules/assets/hooks';
import { assetsDict } from '@/modules/assets/i18n';
import { useLoad } from '@/modules/assets/lib';
import { ASSET_READ, ASSET_WRITE, hasRole } from '@/modules/assets/roles';
import type { AssetListParams, AssetStatus } from '@/modules/assets/types';
import { ApiErrorView } from './ApiErrorView';
import { AssetStateBadges } from './AssetBadges';
import { SELECT_CLASS } from './styles';

interface Draft {
  q: string;
  centerId: string;
  status: AssetStatus | '';
  category: string;
  includeDecommissioned: boolean;
}
const INITIAL: Draft = { q: '', centerId: '', status: '', category: '', includeDecommissioned: false };
const PAGE_SIZE = 20;

export function AssetListView() {
  const { t, lang } = useModuleT(assetsDict);
  const { t: te } = useModuleT(enumDict);
  const role = useAuthStore((s) => s.user?.role);
  const centers = useCenters();
  const [draft, setDraft] = useState<Draft>(INITIAL);
  const [applied, setApplied] = useState<Draft>(INITIAL);
  const [page, setPage] = useState(0);

  const allowed = hasRole(role, ASSET_READ);
  const fetcher = useCallback(() => {
    const p: AssetListParams = {
      q: applied.q.trim(), centerId: applied.centerId, status: applied.status, category: applied.category.trim(),
      includeDecommissioned: applied.includeDecommissioned || undefined, page, size: PAGE_SIZE,
    };
    return assetsApi.list(p);
  }, [applied, page]);
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
        {hasRole(role, ASSET_WRITE) && (
          <Link
            href="/assets/new"
            className="inline-flex h-8 max-md:min-h-10 items-center rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            {t('add')}
          </Link>
        )}
      </div>

      <form onSubmit={submit} role="search" aria-label={t('list')} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2 lg:grid-cols-5">
        <FormField label={t('search')} className="lg:col-span-2">
          <Input type="search" value={draft.q} onChange={(e) => setDraft({ ...draft, q: e.target.value })} />
        </FormField>
        {centerList.length !== 1 && (
          <FormField label={t('center')}>
            <select className={SELECT_CLASS} value={draft.centerId} onChange={(e) => setDraft({ ...draft, centerId: e.target.value })}>
              <option value="">{t('allCenters')}</option>
              {centerList.map((c) => <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>)}
            </select>
          </FormField>
        )}
        <FormField label={t('status')}>
          <select className={SELECT_CLASS} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value as AssetStatus | '' })}>
            <option value="">{t('allStatuses')}</option>
            {ASSET_STATUSES.map((s) => <option key={s} value={s}>{enumLabel(te, 'assetStatus', s)}</option>)}
          </select>
        </FormField>
        <FormField label={t('category')}>
          <Input value={draft.category} onChange={(e) => setDraft({ ...draft, category: e.target.value })} />
        </FormField>
        <label className="flex min-h-10 items-center gap-2 text-sm sm:col-span-2 lg:col-span-4">
          <input
            type="checkbox"
            className="size-4"
            checked={draft.includeDecommissioned}
            onChange={(e) => setDraft({ ...draft, includeDecommissioned: e.target.checked })}
          />
          {t('includeDecommissioned')}
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
                  <TableHead scope="col">{t('code')}</TableHead>
                  <TableHead scope="col">{t('assetName')}</TableHead>
                  <TableHead scope="col">{t('center')}</TableHead>
                  <TableHead scope="col">{t('category')}</TableHead>
                  <TableHead scope="col">{t('status')}</TableHead>
                  <TableHead scope="col">{t('openOrders')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell className="font-mono text-xs">{a.assetCode ?? '-'}</TableCell>
                    <TableCell>
                      <Link href={`/assets/${a.id}`} className="font-medium text-primary underline-offset-4 hover:underline">
                        {lang === 'ar' ? a.nameAr || a.name : a.name || a.nameAr}
                      </Link>
                      {a.location && <p className="text-xs text-muted-foreground">{a.location}</p>}
                    </TableCell>
                    <TableCell>{lang === 'ar' ? a.center?.nameAr : a.center?.name}</TableCell>
                    <TableCell>{a.category ?? '-'}</TableCell>
                    <TableCell><AssetStateBadges asset={a} /></TableCell>
                    <TableCell>{a.openWorkOrders}</TableCell>
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
