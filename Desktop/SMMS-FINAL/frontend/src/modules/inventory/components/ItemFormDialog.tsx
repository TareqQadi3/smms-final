'use client';

import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { mapApiError } from '../errors';
import { inventoryDict } from '../i18n';
import { Banner, localName, NativeSelect, useApiData, useCenterOptions } from '../shared';
import type { InventoryItem } from '../types';

const SKU_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]*$/;

/** Create (item undefined) or edit a catalog item. Stock is never entered here: it enters through receipts. */
export function ItemFormDialog({
  open,
  onOpenChange,
  item,
  onSaved,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  item?: InventoryItem;
  onSaved: (i: InventoryItem) => void;
}) {
  const { t, dir, isAr } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const user = useAuthStore((s) => s.user);
  const { centers } = useCenterOptions();
  const uoms = useApiData(() => inventoryApi.listUoms({ active: true, size: 100 }), [], open);
  const editing = !!item;

  const [f, setF] = useState({ centerId: '', sku: '', name: '', nameAr: '', category: '', uomId: '', reorderLevel: '0', active: true });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    setErrors({});
    setServerError(null);
    setF({
      centerId: item?.center?.id ?? user?.center?.id ?? '',
      sku: item?.sku ?? '',
      name: item?.name ?? '',
      nameAr: item?.nameAr ?? '',
      category: item?.category ?? '',
      uomId: item?.uomId ?? '',
      reorderLevel: String(item?.reorderLevel ?? 0),
      active: item?.active ?? true,
    });
  }, [open, item, user?.center?.id]);

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF((p) => ({ ...p, [k]: e.target.value }));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!editing && !f.centerId) err.centerId = t('errRequired');
    if (!editing) {
      if (!f.sku.trim()) err.sku = t('errRequired');
      else if (!SKU_RE.test(f.sku.trim())) err.sku = t('errSku');
    }
    if (!f.name.trim()) err.name = t('errRequired');
    if (!f.nameAr.trim()) err.nameAr = t('errRequired');
    const rl = Number(f.reorderLevel.replace(',', '.'));
    if (f.reorderLevel.trim() === '' || !Number.isFinite(rl) || rl < 0 || rl > 1_000_000) err.reorderLevel = t('errReorder');
    setErrors(err);
    if (Object.keys(err).length) return;
    setSaving(true);
    setServerError(null);
    try {
      const body = {
        name: f.name.trim(),
        nameAr: f.nameAr.trim(),
        category: f.category.trim() || undefined,
        uomId: f.uomId || undefined,
        reorderLevel: rl,
      };
      const saved = editing
        ? await inventoryApi.updateItem(item!.id, { ...body, active: f.active })
        : await inventoryApi.createItem({ ...body, centerId: f.centerId, sku: f.sku.trim() });
      onSaved(saved);
      onOpenChange(false);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !saving && onOpenChange(o)}>
      <DialogContent dir={dir} className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{editing ? t('editItem') : t('newItem')}</DialogTitle>
          <DialogDescription>{t('itemNoStockHint')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3" aria-label={editing ? t('editItem') : t('newItem')}>
          {serverError && <Banner kind="error">{serverError}</Banner>}
          {!editing && (
            <FormField label={t('center')} required error={errors.centerId}>
              <NativeSelect value={f.centerId} onChange={set('centerId')}>
                <option value="">{t('choose')}</option>
                {centers.map((c) => (
                  <option key={c.id} value={c.id}>
                    {localName(c, isAr)}
                  </option>
                ))}
              </NativeSelect>
            </FormField>
          )}
          <FormField label={t('sku')} required error={errors.sku} hint={editing ? t('skuImmutable') : undefined}>
            <Input value={f.sku} onChange={set('sku')} disabled={editing} dir="ltr" maxLength={80} />
          </FormField>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label={t('nameAr')} required error={errors.nameAr}>
              <Input value={f.nameAr} onChange={set('nameAr')} maxLength={200} lang="ar" />
            </FormField>
            <FormField label={t('nameEn')} required error={errors.name}>
              <Input value={f.name} onChange={set('name')} maxLength={200} dir="ltr" lang="en" />
            </FormField>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label={t('category')}>
              <Input value={f.category} onChange={set('category')} maxLength={80} />
            </FormField>
            <FormField label={t('uom')}>
              <NativeSelect value={f.uomId} onChange={set('uomId')}>
                <option value="">{t('none')}</option>
                {(uoms.data?.content ?? []).map((u) => (
                  <option key={u.id} value={u.id}>
                    {localName(u, isAr)} ({u.code})
                  </option>
                ))}
              </NativeSelect>
            </FormField>
          </div>
          <FormField label={t('reorderLevel')} required error={errors.reorderLevel}>
            <Input value={f.reorderLevel} onChange={set('reorderLevel')} inputMode="decimal" dir="ltr" />
          </FormField>
          {editing && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.active} onChange={(e) => setF((p) => ({ ...p, active: e.target.checked }))} />
              {t('active')}
            </label>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>
              {t('cancel')}
            </Button>
            <Button type="submit" disabled={saving}>
              {saving ? t('saving') : t('save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
