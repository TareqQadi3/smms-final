'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Plus } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { can } from '@/modules/inventory/permissions';
import { DataState, fmtMoney, localName, NativeSelect, PageHeader, useApiData } from '@/modules/inventory/shared';
import { procurementApi } from '../api';
import { procurementDict } from '../i18n';
import { PO_STATUSES } from '../types';
import { PoStatusBadge } from './PoStatusBadge';

export function PurchaseOrdersList() {
  const { t, isAr, lang } = useModuleT(procurementDict);
  const role = useAuthStore((s) => s.user?.role);
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const rangeInvalid = !!from && !!to && from > to;
  const { data, loading, error, reload } = useApiData(
    () => procurementApi.listPos({ status, from, to, page, size: 20, sort: 'createdAt,desc' }),
    [status, from, to, page],
    !rangeInvalid
  );
  const rows = data?.content ?? [];
  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <PageHeader
        title={t('posTitle')}
        subtitle={data ? t('posCount', { n: data.totalElements }) : undefined}
        actions={
          <>
            <Link href="/procurement/suppliers" className="text-sm text-primary hover:underline">{t('suppliersTitle')}</Link>
            {can.editPo(role) && (
              <Link href="/procurement/purchase-orders/new" className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground max-md:min-h-10">
                <Plus size={16} aria-hidden="true" />
                {t('newPo')}
              </Link>
            )}
          </>
        }
      />
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 p-4">
          <FormField label={t('status')} className="w-48">
            <NativeSelect value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
              <option value="">{t('all')}</option>
              {PO_STATUSES.map((s) => (
                <option key={s} value={s}>{t(`status_${s}`)}</option>
              ))}
            </NativeSelect>
          </FormField>
          <FormField label={t('from')} error={rangeInvalid ? t('errRange') : undefined}>
            <Input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          </FormField>
          <FormField label={t('to')}>
            <Input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
          </FormField>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noPos')} emptyDescription={can.editPo(role) ? t('noPosHint') : undefined}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('poNumber')}</TableHead>
                  <TableHead>{t('supplier')}</TableHead>
                  <TableHead>{t('center')}</TableHead>
                  <TableHead>{t('status')}</TableHead>
                  <TableHead>{t('expectedDate')}</TableHead>
                  <TableHead>{t('total')}</TableHead>
                  <TableHead>{t('createdAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((p) => (
                  <TableRow key={p.id}>
                    <TableCell dir="ltr" className="text-start">
                      <Link href={`/procurement/purchase-orders/${p.id}`} className="font-mono text-primary hover:underline">{p.poNumber}</Link>
                    </TableCell>
                    <TableCell>{localName(p.supplier, isAr)}</TableCell>
                    <TableCell>{localName(p.center, isAr)}</TableCell>
                    <TableCell><PoStatusBadge status={p.status} /></TableCell>
                    <TableCell>{formatDate(p.expectedDate, lang)}</TableCell>
                    <TableCell dir="ltr" className="text-start">{fmtMoney(p.total)}</TableCell>
                    <TableCell>{formatDate(p.createdAt, lang)}</TableCell>
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
