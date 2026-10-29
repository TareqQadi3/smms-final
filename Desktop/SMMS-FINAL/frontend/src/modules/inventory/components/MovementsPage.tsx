'use client';

import { useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';
import { Banner, DataState, localName, NativeSelect, PageHeader, useApiData } from '../shared';
import { MOVEMENT_KINDS, type Movement } from '../types';
import { MovementsTable } from './MovementsTable';
import { ReverseDialog } from './ReverseDialog';

export function MovementsPage() {
  const { t, isAr } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const sp = useSearchParams();
  const itemId = sp?.get('itemId') ?? '';
  const [kind, setKind] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [target, setTarget] = useState<Movement | null>(null);
  const [reversedNotice, setReversedNotice] = useState(false);

  const warehouses = useApiData(() => inventoryApi.listWarehouses({ size: 100 }), [], can.listWarehouses(role));
  const rangeInvalid = !!from && !!to && from > to;
  const { data, loading, error, reload } = useApiData(
    () => inventoryApi.listMovements({ itemId, kind, warehouseId, from, to, page, size: 20, sort: 'createdAt,desc' }),
    [itemId, kind, warehouseId, from, to, page],
    !rangeInvalid
  );
  const rows = data?.content ?? [];

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <PageHeader title={t('movementsTitle')} subtitle={itemId ? t('filteredByItem') : t('movementsHint')} />
      {reversedNotice && <Banner kind="success">{t('reverseDone')}</Banner>}
      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 p-4">
          <FormField label={t('kind')} className="w-44">
            <NativeSelect value={kind} onChange={(e) => { setKind(e.target.value); setPage(0); }}>
              <option value="">{t('all')}</option>
              {MOVEMENT_KINDS.map((k) => (
                <option key={k} value={k}>{t(`kind_${k}`)}</option>
              ))}
            </NativeSelect>
          </FormField>
          {can.listWarehouses(role) && (
            <FormField label={t('warehouse')} className="w-52">
              <NativeSelect value={warehouseId} onChange={(e) => { setWarehouseId(e.target.value); setPage(0); }}>
                <option value="">{t('all')}</option>
                {(warehouses.data?.content ?? []).map((w) => (
                  <option key={w.id} value={w.id}>{localName(w, isAr)}</option>
                ))}
              </NativeSelect>
            </FormField>
          )}
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
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noMovements')} emptyDescription={t('noMovementsHint')}>
            <MovementsTable rows={rows} onReverse={can.correct(role) ? setTarget : undefined} />
            <Pagination page={page} totalPages={data?.totalPages ?? 0} onPageChange={setPage} />
          </DataState>
        </CardContent>
      </Card>
      <ReverseDialog
        movement={target}
        onClose={() => setTarget(null)}
        onDone={() => {
          setReversedNotice(true);
          reload();
        }}
      />
    </div>
  );
}
