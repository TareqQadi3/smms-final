'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { mapApiError } from '../errors';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';
import { Banner, DataState, fmtQty, localName, NativeSelect, PageHeader, useApiData } from '../shared';
import { ItemFormDialog } from './ItemFormDialog';
import { MovementsTable } from './MovementsTable';
import type { BalanceLine } from '../types';

function MinQtyCell({ itemId, line, onSaved }: { itemId: string; line: BalanceLine; onSaved: () => void }) {
  const { t } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const [v, setV] = useState(String(line.minQty ?? 0));
  const [err, setErr] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [ok, setOk] = useState(false);
  const save = async () => {
    const n = Number(v.replace(',', '.'));
    if (v.trim() === '' || !Number.isFinite(n) || n < 0) return setErr(t('errReorder'));
    setErr(undefined);
    setBusy(true);
    setOk(false);
    try {
      await inventoryApi.setMinQty({ warehouseId: line.warehouse.id, itemId, minQty: n });
      setOk(true);
      onSaved();
    } catch (e) {
      setErr(mapApiError(e, lang).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex items-start gap-2">
      <FormField label={<span className="sr-only">{t('minQtyFor', { warehouse: line.warehouse.code })}</span>} error={err}>
        <Input value={v} onChange={(e) => { setV(e.target.value); setOk(false); }} inputMode="decimal" dir="ltr" className="w-24" />
      </FormField>
      <Button size="sm" variant="outline" disabled={busy} onClick={save}>
        {busy ? t('saving') : t('save')}
      </Button>
      {ok && <span role="status" className="self-center text-xs text-emerald-700">{t('saved')}</span>}
    </div>
  );
}

function StockCardSection({ itemId, balances }: { itemId: string; balances: BalanceLine[] }) {
  const { t } = useModuleT(inventoryDict);
  const [wh, setWh] = useState(balances[0]?.warehouse.id ?? '');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const card = useApiData(
    () => inventoryApi.stockCard(itemId, { warehouseId: wh, from, to, page, size: 20 }),
    [itemId, wh, from, to, page],
    !!wh
  );
  if (balances.length === 0) return null;
  const rangeInvalid = !!from && !!to && from > to;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('stockCard')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <FormField label={t('warehouse')} className="w-56">
            <NativeSelect value={wh} onChange={(e) => { setWh(e.target.value); setPage(0); }}>
              {balances.map((b) => (
                <option key={b.warehouse.id} value={b.warehouse.id}>
                  {b.warehouse.code} - {b.warehouse.name}
                </option>
              ))}
            </NativeSelect>
          </FormField>
          <FormField label={t('from')} error={rangeInvalid ? t('errRange') : undefined}>
            <Input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} />
          </FormField>
          <FormField label={t('to')}>
            <Input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} />
          </FormField>
        </div>
        {!rangeInvalid && (
          <DataState loading={card.loading} error={card.error} onRetry={card.reload}>
            {card.data && (
              <>
                <p className="text-sm">
                  {t('opening')}: <b dir="ltr">{fmtQty(card.data.openingBalance)}</b> · {t('closing')}: <b dir="ltr">{fmtQty(card.data.closingBalance)}</b>
                </p>
                {card.data.entries.content.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t('noMovements')}</p>
                ) : (
                  <MovementsTable rows={card.data.entries.content} showItem={false} />
                )}
                <Pagination page={page} totalPages={card.data.entries.totalPages} onPageChange={setPage} />
              </>
            )}
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

export function ItemDetail({ id }: { id: string }) {
  const { t, isAr } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const { data: item, loading, error, reload } = useApiData(() => inventoryApi.getItem(id), [id]);
  const [edit, setEdit] = useState(false);
  const showStock = can.readStock(role);

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <DataState loading={loading} error={error} onRetry={reload}>
        {item && (
          <>
            <PageHeader
              title={localName(item, isAr)}
              subtitle={`${item.sku}${item.center ? ' · ' + localName(item.center, isAr) : ''}`}
              actions={
                <>
                  <Link href="/inventory" className="text-sm text-primary hover:underline">
                    {t('backToItems')}
                  </Link>
                  {showStock && (
                    <Link href={`/inventory/movements?itemId=${item.id}`} className="text-sm text-primary hover:underline">
                      {t('movementsTitle')}
                    </Link>
                  )}
                  {can.writeCatalog(role) && (
                    <Button variant="outline" onClick={() => setEdit(true)}>
                      {t('editItem')}
                    </Button>
                  )}
                </>
              }
            />
            <Card>
              <CardContent className="grid gap-3 p-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
                <div><span className="text-muted-foreground">{t('category')}: </span>{item.category ?? '-'}</div>
                <div><span className="text-muted-foreground">{t('uom')}: </span>{item.uom ?? '-'}</div>
                <div><span className="text-muted-foreground">{t('reorderLevel')}: </span>{fmtQty(item.reorderLevel)}</div>
                <div className="flex gap-1">
                  <Badge variant={item.active ? 'secondary' : 'outline'}>{item.active ? t('active') : t('inactive')}</Badge>
                  {item.lowStock && <Badge variant="destructive">{t('lowStock')}</Badge>}
                </div>
                {showStock && (
                  <>
                    <div><span className="text-muted-foreground">{t('onHand')}: </span><b>{fmtQty(item.totalOnHand)}</b></div>
                    <div><span className="text-muted-foreground">{t('reserved')}: </span><b>{fmtQty(item.totalReserved)}</b></div>
                    <div><span className="text-muted-foreground">{t('available')}: </span><b>{fmtQty(item.totalAvailable)}</b></div>
                  </>
                )}
              </CardContent>
            </Card>

            {showStock && (
              <Card>
                <CardHeader>
                  <CardTitle>{t('balancesByWarehouse')}</CardTitle>
                </CardHeader>
                <CardContent>
                  {(item.balances ?? []).length === 0 ? (
                    <Banner kind="info">{t('noBalances')}</Banner>
                  ) : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{t('warehouse')}</TableHead>
                          <TableHead>{t('onHand')}</TableHead>
                          <TableHead>{t('reserved')}</TableHead>
                          <TableHead>{t('available')}</TableHead>
                          <TableHead>{t('minQty')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {item.balances!.map((b) => (
                          <TableRow key={b.warehouse.id}>
                            <TableCell>{localName(b.warehouse, isAr)} <span className="text-xs text-muted-foreground">({b.warehouse.code})</span></TableCell>
                            <TableCell>{fmtQty(b.onHand)}</TableCell>
                            <TableCell>{fmtQty(b.reserved)}</TableCell>
                            <TableCell>{fmtQty(b.available)}</TableCell>
                            <TableCell>
                              {can.writeCatalog(role) ? <MinQtyCell itemId={item.id} line={b} onSaved={reload} /> : fmtQty(b.minQty)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            )}

            {showStock && <StockCardSection itemId={item.id} balances={item.balances ?? []} />}

            <ItemFormDialog open={edit} onOpenChange={setEdit} item={item} onSaved={reload} />
          </>
        )}
      </DataState>
    </div>
  );
}
