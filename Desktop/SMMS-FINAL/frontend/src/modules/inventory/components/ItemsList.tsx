'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Plus, Search } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';
import { Banner, DataState, fmtQty, localName, NativeSelect, PageHeader, useApiData } from '../shared';
import { ItemFormDialog } from './ItemFormDialog';

const SIZE = 20;

export function ItemsList() {
  const { t, isAr } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const [q, setQ] = useState('');
  const [applied, setApplied] = useState('');
  const [lowStock, setLowStock] = useState('');
  const [active, setActive] = useState('true');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState(false);
  const [created, setCreated] = useState<string | null>(null);

  const { data, loading, error, reload } = useApiData(
    () => inventoryApi.listItems({ q: applied || undefined, lowStock: lowStock || undefined, active: active || undefined, page, size: SIZE, sort: 'name,asc' }),
    [applied, lowStock, active, page]
  );
  const showStock = can.readStock(role);
  const items = data?.content ?? [];

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <PageHeader
        title={t('itemsTitle')}
        subtitle={data ? t('itemsCount', { n: data.totalElements }) : undefined}
        actions={
          <>
            {can.receipt(role) && (
              <Link href="/inventory/receipts/new" className="inline-flex h-8 items-center rounded-lg border px-3 text-sm hover:bg-muted max-md:min-h-10">
                {t('newReceipt')}
              </Link>
            )}
            {can.writeCatalog(role) && (
              <Button onClick={() => setDialog(true)}>
                <Plus size={16} aria-hidden="true" />
                {t('newItem')}
              </Button>
            )}
          </>
        }
      />
      {created && <Banner kind="success">{t('itemCreated', { name: created })}</Banner>}
      <Card>
        <CardContent className="p-4">
          <form
            role="search"
            aria-label={t('filters')}
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              setPage(0);
              setApplied(q.trim());
            }}
          >
            <div className="relative min-w-[200px] flex-1">
              <label htmlFor="items-q" className="sr-only">
                {t('search')}
              </label>
              <Search size={15} aria-hidden="true" className="absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <Input id="items-q" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('searchItems')} className="ps-9" />
            </div>
            <div className="w-40">
              <label htmlFor="items-low" className="sr-only">
                {t('stockLevel')}
              </label>
              <NativeSelect id="items-low" value={lowStock} onChange={(e) => { setLowStock(e.target.value); setPage(0); }}>
                <option value="">{t('allStock')}</option>
                <option value="true">{t('lowOnly')}</option>
              </NativeSelect>
            </div>
            <div className="w-40">
              <label htmlFor="items-active" className="sr-only">
                {t('status')}
              </label>
              <NativeSelect id="items-active" value={active} onChange={(e) => { setActive(e.target.value); setPage(0); }}>
                <option value="true">{t('activeOnly')}</option>
                <option value="false">{t('inactiveOnly')}</option>
                <option value="">{t('allStatuses')}</option>
              </NativeSelect>
            </div>
            <Button type="submit" variant="secondary">
              {t('search')}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={items.length === 0} emptyTitle={t('noItems')} emptyDescription={t('noItemsHint')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('sku')}</TableHead>
                  <TableHead>{t('name')}</TableHead>
                  <TableHead>{t('category')}</TableHead>
                  <TableHead>{t('uom')}</TableHead>
                  {showStock && <TableHead>{t('onHand')}</TableHead>}
                  {showStock && <TableHead>{t('reserved')}</TableHead>}
                  {showStock && <TableHead>{t('available')}</TableHead>}
                  <TableHead>{t('reorderLevel')}</TableHead>
                  <TableHead>{t('status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((i) => (
                  <TableRow key={i.id}>
                    <TableCell dir="ltr" className="text-start font-mono text-xs">{i.sku}</TableCell>
                    <TableCell>
                      <Link href={`/inventory/${i.id}`} className="font-medium text-primary hover:underline">
                        {localName(i, isAr)}
                      </Link>
                    </TableCell>
                    <TableCell>{i.category ?? '-'}</TableCell>
                    <TableCell>{i.uom ?? '-'}</TableCell>
                    {showStock && <TableCell>{fmtQty(i.totalOnHand)}</TableCell>}
                    {showStock && <TableCell>{fmtQty(i.totalReserved)}</TableCell>}
                    {showStock && <TableCell>{fmtQty(i.totalAvailable)}</TableCell>}
                    <TableCell>{fmtQty(i.reorderLevel)}</TableCell>
                    <TableCell>
                      <div className="flex gap-1">
                        <Badge variant={i.active ? 'secondary' : 'outline'}>{i.active ? t('active') : t('inactive')}</Badge>
                        {i.lowStock && <Badge variant="destructive">{t('lowStock')}</Badge>}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={page} totalPages={data?.totalPages ?? 0} onPageChange={setPage} />
          </DataState>
        </CardContent>
      </Card>

      <ItemFormDialog
        open={dialog}
        onOpenChange={setDialog}
        onSaved={(i) => {
          setCreated(localName(i, isAr));
          reload();
        }}
      />
    </div>
  );
}
