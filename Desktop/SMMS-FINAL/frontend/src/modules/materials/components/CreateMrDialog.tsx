'use client';

import { useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { mapApiError } from '@/modules/inventory/errors';
import { can } from '@/modules/inventory/permissions';
import { Banner, localName, NativeSelect, parseQty, useApiData, useItemOptions } from '@/modules/inventory/shared';
import { inventoryApi } from '@/modules/inventory/api';
import { materialsApi } from '../api';
import { materialsDict } from '../i18n';
import type { MaterialRequest } from '../types';

interface Line {
  key: number;
  itemId: string;
  qty: string;
}

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * New material request for one work order. Roles that cannot list warehouses (technician, control
 * room) get the warehouse of an earlier request of the same work order, or a manual warehouse id.
 */
export function CreateMrDialog({
  open,
  onOpenChange,
  workOrderId,
  centerId,
  knownWarehouseIds = [],
  onCreated,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  workOrderId: string;
  centerId?: string;
  knownWarehouseIds?: string[];
  onCreated: (mr: MaterialRequest) => void;
}) {
  const { t, dir, isAr } = useModuleT(materialsDict);
  const lang = useAuthStore((s) => s.lang);
  const user = useAuthStore((s) => s.user);
  const effectiveCenter = centerId ?? user?.center?.id;
  const listable = can.listWarehouses(user?.role);
  const whs = useApiData(() => inventoryApi.listWarehouses({ centerId: effectiveCenter, active: true, size: 100 }), [effectiveCenter], open && listable);
  const items = useItemOptions(open ? effectiveCenter : undefined);

  const [warehouseId, setWarehouseId] = useState('');
  const [manualWh, setManualWh] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<Line[]>([{ key: 1, itemId: '', qty: '' }]);
  const [nextKey, setNextKey] = useState(2);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setErrors({});
    setServerError(null);
    setNote('');
    setLines([{ key: 1, itemId: '', qty: '' }]);
    setNextKey(2);
    setWarehouseId(knownWarehouseIds[0] ?? '');
    setManualWh('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const setLine = (key: number, patch: Partial<Line>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  const useManual = !listable && knownWarehouseIds.length === 0;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    const wh = useManual ? manualWh.trim() : warehouseId;
    if (!wh) err.warehouse = t('errRequired');
    else if (useManual && !UUID_RE.test(wh)) err.warehouse = t('errUuid');
    const payload: { itemId: string; quantity: number }[] = [];
    lines.forEach((l) => {
      if (!l.itemId) err[`item${l.key}`] = t('errRequired');
      const q = parseQty(l.qty);
      if (q.error) err[`qty${l.key}`] = q.error === 'required' ? t('errRequired') : q.error === 'decimals' ? t('errDecimals') : q.error === 'max' ? t('errMaxQty') : t('errPositive');
      else if (l.itemId) payload.push({ itemId: l.itemId, quantity: q.value! });
    });
    const ids = lines.map((l) => l.itemId).filter(Boolean);
    if (new Set(ids).size !== ids.length) err.lines = t('errDuplicateItems');
    setErrors(err);
    if (Object.keys(err).length) return;
    setBusy(true);
    setServerError(null);
    try {
      const mr = await materialsApi.create(workOrderId, { warehouseId: wh, note: note.trim() || undefined, lines: payload });
      onCreated(mr);
      onOpenChange(false);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir} className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('newMr')}</DialogTitle>
          <DialogDescription>{t('newMrDesc')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3" aria-label={t('newMr')}>
          {serverError && <Banner kind="error">{serverError}</Banner>}
          {whs.error && <Banner kind="error">{whs.error.message}</Banner>}
          {items.error && <Banner kind="error">{items.error.message}</Banner>}
          {useManual ? (
            <FormField label={t('warehouseId')} required error={errors.warehouse} hint={t('warehouseIdHint')}>
              <Input value={manualWh} onChange={(e) => setManualWh(e.target.value)} dir="ltr" />
            </FormField>
          ) : (
            <FormField label={t('warehouse')} required error={errors.warehouse}>
              <NativeSelect value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
                <option value="">{t('choose')}</option>
                {listable
                  ? (whs.data?.content ?? []).map((w) => (
                      <option key={w.id} value={w.id}>{localName(w, isAr)} ({w.code})</option>
                    ))
                  : knownWarehouseIds.map((w) => (
                      <option key={w} value={w}>{t('sameWarehouse')}</option>
                    ))}
              </NativeSelect>
            </FormField>
          )}
          <FormField label={t('note')}>
            <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={1000} />
          </FormField>
          <fieldset className="space-y-2">
            <legend className="mb-1 text-sm font-medium">{t('lines')}</legend>
            {errors.lines && <p role="alert" className="text-xs text-destructive">{errors.lines}</p>}
            {lines.map((l, idx) => (
              <div key={l.key} className="grid items-start gap-2 sm:grid-cols-[1fr_7rem_auto]">
                <FormField label={`${t('item')} ${idx + 1}`} required error={errors[`item${l.key}`]}>
                  <NativeSelect value={l.itemId} onChange={(e) => setLine(l.key, { itemId: e.target.value })}>
                    <option value="">{t('choose')}</option>
                    {(items.data?.content ?? []).map((i) => (
                      <option key={i.id} value={i.id}>{i.sku} - {localName(i, isAr)}</option>
                    ))}
                  </NativeSelect>
                </FormField>
                <FormField label={t('quantity')} required error={errors[`qty${l.key}`]}>
                  <Input value={l.qty} onChange={(e) => setLine(l.key, { qty: e.target.value })} inputMode="decimal" dir="ltr" />
                </FormField>
                <Button type="button" variant="ghost" size="icon" className="mt-6" aria-label={t('removeLine', { n: idx + 1 })} disabled={lines.length === 1} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}>
                  <Trash2 size={16} aria-hidden="true" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" disabled={lines.length >= 30} onClick={() => { setLines((ls) => [...ls, { key: nextKey, itemId: '', qty: '' }]); setNextKey((n) => n + 1); }}>
              <Plus size={14} aria-hidden="true" />
              {t('addLine')}
            </Button>
          </fieldset>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>{t('cancel')}</Button>
            <Button type="submit" disabled={busy}>{busy ? t('saving') : t('submitRequest')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
