'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { FormField, PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '@/modules/inventory/api';
import { mapApiError } from '@/modules/inventory/errors';
import { can } from '@/modules/inventory/permissions';
import { Banner, DataState, localName, NativeSelect, PageHeader, parseQty, useApiData, useCenterOptions, useItemOptions } from '@/modules/inventory/shared';
import { procurementApi } from '../api';
import { procurementDict } from '../i18n';

interface Line {
  key: number;
  itemId: string;
  qty: string;
  price: string;
}

/** Create a draft purchase order, or edit one (draft only; `poId` set). Saves as draft; submit happens on the detail page. */
export function PurchaseOrderForm({ poId }: { poId?: string }) {
  const { t, isAr } = useModuleT(procurementDict);
  const lang = useAuthStore((s) => s.lang);
  const user = useAuthStore((s) => s.user);
  const router = useRouter();
  const editing = !!poId;
  const allowed = can.editPo(user?.role);
  const existing = useApiData(() => procurementApi.getPo(poId!), [poId], editing && allowed);
  const { centers } = useCenterOptions();

  const [centerId, setCenterId] = useState(user?.center?.id ?? '');
  const [supplierId, setSupplierId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [expectedDate, setExpectedDate] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<Line[]>([{ key: 1, itemId: '', qty: '', price: '' }]);
  const [nextKey, setNextKey] = useState(2);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const suppliers = useApiData(() => procurementApi.listSuppliers({ active: true, size: 200, sort: 'name,asc' }), [], allowed);
  const warehouses = useApiData(() => inventoryApi.listWarehouses({ centerId, active: true, size: 100 }), [centerId], allowed && !!centerId);
  const items = useItemOptions(centerId || undefined);

  const po = existing.data;
  useEffect(() => {
    if (!po) return;
    setCenterId(po.center?.id ?? '');
    setSupplierId(po.supplier?.id ?? '');
    setWarehouseId(po.warehouse?.id ?? '');
    setExpectedDate(po.expectedDate ?? '');
    setNotes(po.notes ?? '');
    setLines(po.lines.map((l, i) => ({ key: i + 1, itemId: l.item.id, qty: String(l.qtyOrdered), price: l.unitPrice !== undefined && l.unitPrice !== null ? String(l.unitPrice) : '' })));
    setNextKey(po.lines.length + 1);
  }, [po]);

  if (!allowed) return <PermissionDenied />;

  const setLine = (key: number, patch: Partial<Line>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!centerId) err.center = t('errRequired');
    if (!supplierId) err.supplier = t('errRequired');
    if (!warehouseId) err.warehouse = t('errRequired');
    const payload: { itemId: string; qtyOrdered: number; unitPrice?: number }[] = [];
    lines.forEach((l) => {
      if (!l.itemId) err[`item${l.key}`] = t('errRequired');
      const q = parseQty(l.qty);
      if (q.error) err[`qty${l.key}`] = q.error === 'required' ? t('errRequired') : q.error === 'decimals' ? t('errDecimals') : q.error === 'max' ? t('errMaxQty') : t('errPositive');
      let price: number | undefined;
      if (l.price.trim() !== '') {
        const p = Number(l.price.replace(',', '.'));
        if (!Number.isFinite(p) || p < 0 || !/^\d+([.,]\d{1,2})?$/.test(l.price.trim())) err[`price${l.key}`] = t('errPrice');
        else price = p;
      }
      if (l.itemId && !q.error && !err[`price${l.key}`]) payload.push({ itemId: l.itemId, qtyOrdered: q.value!, unitPrice: price });
    });
    const ids = lines.map((l) => l.itemId).filter(Boolean);
    if (new Set(ids).size !== ids.length) err.lines = t('errDuplicateItems');
    setErrors(err);
    if (Object.keys(err).length) return;
    const body = { centerId, supplierId, warehouseId, expectedDate: expectedDate || undefined, notes: notes.trim() || undefined, lines: payload };
    setBusy(true);
    setServerError(null);
    try {
      const saved = editing ? await procurementApi.updatePo(poId!, { ...body, version: po?.version }) : await procurementApi.createPo(body);
      router.push(`/procurement/purchase-orders/${saved.id}`);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
      setBusy(false);
    }
  };

  const notDraft = editing && po && po.status !== 'draft';

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <PageHeader
        title={editing ? t('editPo') : t('newPo')}
        subtitle={t('poFormHint')}
        actions={<Link href={editing ? `/procurement/purchase-orders/${poId}` : '/procurement/purchase-orders'} className="text-sm text-primary hover:underline">{t('back')}</Link>}
      />
      <DataState loading={editing && existing.loading} error={existing.error} onRetry={existing.reload}>
        {notDraft ? (
          <Banner kind="error">{t('onlyDraftEditable')}</Banner>
        ) : (
          <Card>
            <CardContent className="p-4">
              <form onSubmit={submit} noValidate className="space-y-4" aria-label={editing ? t('editPo') : t('newPo')}>
                {serverError && <Banner kind="error">{serverError}</Banner>}
                <div className="grid gap-3 sm:grid-cols-2">
                  <FormField label={t('center')} required error={errors.center}>
                    <NativeSelect
                      value={centerId}
                      disabled={editing}
                      onChange={(e) => {
                        setCenterId(e.target.value);
                        setWarehouseId('');
                        setLines((ls) => ls.map((l) => ({ ...l, itemId: '' })));
                      }}
                    >
                      <option value="">{t('choose')}</option>
                      {centers.map((c) => (
                        <option key={c.id} value={c.id}>{localName(c, isAr)}</option>
                      ))}
                    </NativeSelect>
                  </FormField>
                  <FormField label={t('warehouse')} required error={errors.warehouse}>
                    <NativeSelect value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)} disabled={!centerId}>
                      <option value="">{t('choose')}</option>
                      {(warehouses.data?.content ?? []).map((w) => (
                        <option key={w.id} value={w.id}>{localName(w, isAr)} ({w.code})</option>
                      ))}
                    </NativeSelect>
                  </FormField>
                  <FormField label={t('supplier')} required error={errors.supplier}>
                    <NativeSelect value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
                      <option value="">{t('choose')}</option>
                      {(suppliers.data?.content ?? []).map((s) => (
                        <option key={s.id} value={s.id}>{localName(s, isAr)} ({s.code})</option>
                      ))}
                    </NativeSelect>
                  </FormField>
                  <FormField label={t('expectedDate')}>
                    <Input type="date" value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} />
                  </FormField>
                </div>
                <FormField label={t('notes')}>
                  <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} maxLength={2000} />
                </FormField>
                <fieldset className="space-y-2">
                  <legend className="mb-1 text-sm font-medium">{t('lines')}</legend>
                  {errors.lines && <p role="alert" className="text-xs text-destructive">{errors.lines}</p>}
                  {items.error && <Banner kind="error">{items.error.message}</Banner>}
                  {lines.map((l, idx) => (
                    <div key={l.key} className="grid items-start gap-2 sm:grid-cols-[1fr_8rem_8rem_auto]">
                      <FormField label={`${t('item')} ${idx + 1}`} required error={errors[`item${l.key}`]}>
                        <NativeSelect value={l.itemId} onChange={(e) => setLine(l.key, { itemId: e.target.value })} disabled={!centerId}>
                          <option value="">{t('choose')}</option>
                          {(items.data?.content ?? []).map((i) => (
                            <option key={i.id} value={i.id}>{i.sku} - {localName(i, isAr)}</option>
                          ))}
                        </NativeSelect>
                      </FormField>
                      <FormField label={t('qtyOrdered')} required error={errors[`qty${l.key}`]}>
                        <Input value={l.qty} onChange={(e) => setLine(l.key, { qty: e.target.value })} inputMode="decimal" dir="ltr" />
                      </FormField>
                      <FormField label={t('unitPrice')} error={errors[`price${l.key}`]}>
                        <Input value={l.price} onChange={(e) => setLine(l.key, { price: e.target.value })} inputMode="decimal" dir="ltr" />
                      </FormField>
                      <Button type="button" variant="ghost" size="icon" className="mt-6" aria-label={t('removeLine', { n: idx + 1 })} disabled={lines.length === 1} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}>
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  ))}
                  <Button type="button" variant="outline" size="sm" disabled={lines.length >= 100} onClick={() => { setLines((ls) => [...ls, { key: nextKey, itemId: '', qty: '', price: '' }]); setNextKey((n) => n + 1); }}>
                    <Plus size={14} aria-hidden="true" />
                    {t('addLine')}
                  </Button>
                </fieldset>
                <Button type="submit" disabled={busy}>{busy ? t('saving') : t('saveDraft')}</Button>
              </form>
            </CardContent>
          </Card>
        )}
      </DataState>
    </div>
  );
}
