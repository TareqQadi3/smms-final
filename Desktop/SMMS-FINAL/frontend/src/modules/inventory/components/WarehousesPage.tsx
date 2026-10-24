'use client';

import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { mapApiError } from '../errors';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';
import { Banner, DataState, localName, NativeSelect, PageHeader, useApiData, useCenterOptions } from '../shared';
import type { Warehouse } from '../types';

const CODE_RE = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;

function WarehouseDialog({ open, onOpenChange, warehouse, onSaved }: { open: boolean; onOpenChange: (o: boolean) => void; warehouse?: Warehouse; onSaved: () => void }) {
  const { t, dir, isAr } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const user = useAuthStore((s) => s.user);
  const { centers } = useCenterOptions();
  const [f, setF] = useState({ centerId: '', code: '', name: '', nameAr: '', active: true });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!open) return;
    setErrors({});
    setServerError(null);
    setF({
      centerId: warehouse?.centerId ?? user?.center?.id ?? '',
      code: warehouse?.code ?? '',
      name: warehouse?.name ?? '',
      nameAr: warehouse?.nameAr ?? '',
      active: warehouse?.active ?? true,
    });
  }, [open, warehouse, user?.center?.id]);
  const set = (k: 'centerId' | 'code' | 'name' | 'nameAr') => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF((p) => ({ ...p, [k]: e.target.value }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.centerId) err.centerId = t('errRequired');
    if (!f.code.trim()) err.code = t('errRequired');
    else if (!CODE_RE.test(f.code.trim())) err.code = t('errCode');
    if (!f.name.trim()) err.name = t('errRequired');
    if (!f.nameAr.trim()) err.nameAr = t('errRequired');
    setErrors(err);
    if (Object.keys(err).length) return;
    setBusy(true);
    setServerError(null);
    try {
      const body = { centerId: f.centerId, code: f.code.trim(), name: f.name.trim(), nameAr: f.nameAr.trim(), active: f.active };
      if (warehouse) await inventoryApi.updateWarehouse(warehouse.id, body);
      else await inventoryApi.createWarehouse(body);
      onSaved();
      onOpenChange(false);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir}>
        <DialogHeader>
          <DialogTitle>{warehouse ? t('editWarehouse') : t('newWarehouse')}</DialogTitle>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          <FormField label={t('center')} required error={errors.centerId}>
            <NativeSelect value={f.centerId} onChange={set('centerId')}>
              <option value="">{t('choose')}</option>
              {centers.map((c) => (
                <option key={c.id} value={c.id}>{localName(c, isAr)}</option>
              ))}
            </NativeSelect>
          </FormField>
          <FormField label={t('code')} required error={errors.code}>
            <Input value={f.code} onChange={set('code')} dir="ltr" maxLength={40} />
          </FormField>
          <FormField label={t('nameAr')} required error={errors.nameAr}>
            <Input value={f.nameAr} onChange={set('nameAr')} maxLength={200} />
          </FormField>
          <FormField label={t('nameEn')} required error={errors.name}>
            <Input value={f.name} onChange={set('name')} dir="ltr" maxLength={200} />
          </FormField>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={f.active} onChange={(e) => setF((p) => ({ ...p, active: e.target.checked }))} />
            {t('active')}
          </label>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>{t('cancel')}</Button>
            <Button type="submit" disabled={busy}>{busy ? t('saving') : t('save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function WarehousesPage() {
  const { t, isAr } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<{ open: boolean; wh?: Warehouse }>({ open: false });
  const [saved, setSaved] = useState(false);
  const { data, loading, error, reload } = useApiData(() => inventoryApi.listWarehouses({ page, size: 20, sort: 'name,asc' }), [page]);
  const rows = data?.content ?? [];
  const writer = can.writeWarehouse(role);
  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <PageHeader
        title={t('warehousesTitle')}
        subtitle={data ? t('warehousesCount', { n: data.totalElements }) : undefined}
        actions={writer && (
          <Button onClick={() => setDialog({ open: true })}>
            <Plus size={16} aria-hidden="true" />
            {t('newWarehouse')}
          </Button>
        )}
      />
      {saved && <Banner kind="success">{t('saved')}</Banner>}
      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noWarehouses')}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('code')}</TableHead>
                  <TableHead>{t('name')}</TableHead>
                  <TableHead>{t('center')}</TableHead>
                  <TableHead>{t('status')}</TableHead>
                  {writer && <TableHead>{t('actions')}</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((w) => (
                  <TableRow key={w.id}>
                    <TableCell dir="ltr" className="text-start font-mono text-xs">{w.code}</TableCell>
                    <TableCell>{localName(w, isAr)}</TableCell>
                    <TableCell>{w.centerName ?? '-'}</TableCell>
                    <TableCell><Badge variant={w.active ? 'secondary' : 'outline'}>{w.active ? t('active') : t('inactive')}</Badge></TableCell>
                    {writer && (
                      <TableCell>
                        <Button size="sm" variant="outline" onClick={() => setDialog({ open: true, wh: w })}>{t('edit')}</Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={page} totalPages={data?.totalPages ?? 0} onPageChange={setPage} />
          </DataState>
        </CardContent>
      </Card>
      <WarehouseDialog
        open={dialog.open}
        onOpenChange={(o) => setDialog((d) => ({ ...d, open: o }))}
        warehouse={dialog.wh}
        onSaved={() => {
          setSaved(true);
          reload();
        }}
      />
    </div>
  );
}
