'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Card, CardContent } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { can } from '@/modules/inventory/permissions';
import { DataState, localName, NativeSelect, PageHeader, useApiData } from '@/modules/inventory/shared';
import { PermissionDenied } from '@/components/common';
import { materialsApi } from '../api';
import { materialsDict } from '../i18n';
import { MR_STATUSES } from '../types';
import { MrStatusBadge } from './MrStatusBadge';

export function MaterialRequestsList() {
  const { t, isAr, lang } = useModuleT(materialsDict);
  const role = useAuthStore((s) => s.user?.role);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const allowed = can.readMr(role);
  const { data, loading, error, reload } = useApiData(
    () => materialsApi.list({ status, page, size: 20, sort: 'createdAt,desc' }),
    [status, page],
    allowed
  );
  if (!allowed) return <PermissionDenied />;
  const rows = data?.content ?? [];
  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader title={t('mrTitle')} subtitle={data ? t('mrCount', { n: data.totalElements }) : t('mrHint')} />
      <Card>
        <CardContent className="p-4">
          <FormField label={t('status')} className="w-56">
            <NativeSelect value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">{t('all')}</option>
              {MR_STATUSES.map((s) => (
                <option key={s} value={s}>{t(`status_${s}`)}</option>
              ))}
            </NativeSelect>
          </FormField>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noMr')} emptyDescription={t('noMrHint')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('requestNumber')}</TableHead>
                  <TableHead>{t('workOrder')}</TableHead>
                  <TableHead>{t('center')}</TableHead>
                  <TableHead>{t('requestedBy')}</TableHead>
                  <TableHead>{t('status')}</TableHead>
                  <TableHead>{t('createdAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell dir="ltr" className="text-start">
                      <Link href={`/material-requests/${r.id}`} className="font-mono text-primary hover:underline">{r.requestNumber}</Link>
                    </TableCell>
                    <TableCell dir="ltr" className="text-start">
                      {r.workOrder ? <Link href={`/work-orders/${r.workOrder.id}`} className="text-primary hover:underline">{r.workOrder.ticketNumber}</Link> : '-'}
                    </TableCell>
                    <TableCell>{localName(r.center, isAr)}</TableCell>
                    <TableCell>{localName(r.requestedBy, isAr)}</TableCell>
                    <TableCell><MrStatusBadge status={r.status} /></TableCell>
                    <TableCell>{formatDate(r.createdAt, lang)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={page} totalPages={data?.totalPages ?? 0} onPageChange={setPage} />
          </DataState>
        </CardContent>
      </Card>
    </div>
  );
}
