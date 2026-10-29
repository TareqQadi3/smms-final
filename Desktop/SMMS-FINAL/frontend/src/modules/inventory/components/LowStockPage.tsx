'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Card, CardContent } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';
import { DataState, fmtQty, localName, PageHeader, useApiData } from '../shared';

export function LowStockPage() {
  const { t, isAr } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useApiData(() => inventoryApi.lowStock({ page, size: 20 }), [page]);
  const rows = data?.content ?? [];
  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader title={t('lowStockTitle')} subtitle={t('lowStockHint')} />
      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noLowStock')} emptyDescription={t('noLowStockHint')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('sku')}</TableHead>
                  <TableHead>{t('name')}</TableHead>
                  <TableHead>{t('center')}</TableHead>
                  <TableHead>{t('available')}</TableHead>
                  <TableHead>{t('reorderLevel')}</TableHead>
                  <TableHead>{t('actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((i) => (
                  <TableRow key={i.id}>
                    <TableCell dir="ltr" className="text-start font-mono text-xs">{i.sku}</TableCell>
                    <TableCell><Link href={`/inventory/${i.id}`} className="text-primary hover:underline">{localName(i, isAr)}</Link></TableCell>
                    <TableCell>{localName(i.center, isAr)}</TableCell>
                    <TableCell className="font-semibold text-destructive">{fmtQty(i.totalAvailable)}</TableCell>
                    <TableCell>{fmtQty(i.reorderLevel)}</TableCell>
                    <TableCell>
                      {can.receipt(role) && (
                        <Link href="/inventory/receipts/new" className="text-primary hover:underline">{t('newReceipt')}</Link>
                      )}
                    </TableCell>
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
