'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { FormField, PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { mapApiError } from '../errors';
import { inventoryDict } from '../i18n';
import { useIdempotencyKey } from '../idempotency';
import { can } from '../permissions';
import { Banner, DataState, localName, NativeSelect, PageHeader, parseQty, useItemOptions, useWarehouseOptions } from '../shared';
import type { Movement } from '../types';

interface Line {
  key: number;
  itemId: string;
  qty: string;
}

/** Goods receipt without a purchase order. Stock enters the ledger only through here (or PO receiving). */
export function ReceiptForm() {
  const { t, isAr } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const role = useAuthStore((s) => s.user?.role);
  const { keyFor, done } = useIdempotencyKey();
  const whs = useWarehouseOptions(can.receipt(role));
  const [warehouseId, setWarehouseId] = useState('');
  const [reference, setReference] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<Line[]>([{ key: 1, itemId: '', qty: '' }]);
  const [nextKey, setNextKey] = useState(2);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Movement[] | null>(null);

  const wh = (whs.data?.content ?? []).find((w) => w.id === warehouseId);
  const items = useItemOptions(wh?.centerId);

  if (!can.receipt(role)) return <PermissionDenied />;

  const setLine = (key: number, patch: Partial<Line>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!warehouseId) err.warehouse = t('errRequired');
    const payload: { itemId: string; quantity: number }[] = [];
    lines.forEach((l) => {
      if (!l.itemId) err[`item${l.key}`] = t('errRequired');
      const q = parseQty(l.qty);
      if (q.error) err[`qty${l.key}`] = t(q.error === 'required' ? 'errRequired' : q.error === 'decimals' ? 'errDecimals' : q.error === 'max' ? 'errMaxQty' : 'errPositive');
      else if (l.itemId) payload.push({ itemId: l.itemId, quantity: q.value! });
    });
    const ids = lines.map((l) => l.itemId).filter(Boolean);
    if (new Set(ids).size !== ids.length) err.lines = t('errDuplicateItems');
    setErrors(err);
    if (Object.keys(err).length) return;
    const body = { warehouseId, reference: reference.trim() || undefined, note: note.trim() || undefined, lines: payload };
    setBusy(true);
    setServerError(null);
    try {
      const r = await inventoryApi.receipt(body, keyFor(body));
      done();
      setResult(r.movements);
      setLines([{ key: nextKey, itemId: '', qty: '' }]);
      setNextKey((n) => n + 1);
      setReference('');
      setNote('');
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <PageHeader title={t('receiptTitle')} subtitle={t('receiptHint')} actions={<Link href="/inventory" className="text-sm text-primary hover:underline">{t('backToItems')}</Link>} />
      {result && (
        <Banner kind="success">
          {t('receiptDone', { n: result.length })}{' '}
          <Link href="/inventory/movements" className="underline">{t('movementsTitle')}</Link>
        </Banner>
      )}
      <DataState loading={whs.loading} error={whs.error} onRetry={whs.reload}>
        <Card>
          <CardContent className="p-4">
            <form onSubmit={submit} noValidate className="space-y-4" aria-label={t('receiptTitle')}>
              {serverError && <Banner kind="error">{serverError}</Banner>}
              <FormField label={t('warehouse')} required error={errors.warehouse}>
                <NativeSelect
                  value={warehouseId}
                  onChange={(e) => {
                    setWarehouseId(e.target.value);
                    setLines((ls) => ls.map((l) => ({ ...l, itemId: '' })));
                  }}
                >
                  <option value="">{t('choose')}</option>
                  {(whs.data?.content ?? []).map((w) => (
                    <option key={w.id} value={w.id}>{localName(w, isAr)} ({w.code})</option>
                  ))}
                </NativeSelect>
              </FormField>
              <div className="grid gap-3 sm:grid-cols-2">
                <FormField label={t('reference')} hint={t('referenceHint')}>
                  <Input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={120} />
                </FormField>
                <FormField label={t('note')}>
                  <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={1} maxLength={1000} />
                </FormField>
              </div>
              <fieldset className="space-y-2">
                <legend className="mb-1 text-sm font-medium">{t('lines')}</legend>
                {errors.lines && <p role="alert" className="text-xs text-destructive">{errors.lines}</p>}
                {items.error && <Banner kind="error">{items.error.message}</Banner>}
                {lines.map((l, idx) => (
                  <div key={l.key} className="grid items-start gap-2 sm:grid-cols-[1fr_9rem_auto]">
                    <FormField label={`${t('item')} ${idx + 1}`} required error={errors[`item${l.key}`]}>
                      <NativeSelect value={l.itemId} onChange={(e) => setLine(l.key, { itemId: e.target.value })} disabled={!warehouseId}>
                        <option value="">{warehouseId ? t('choose') : t('chooseWarehouseFirst')}</option>
                        {(items.data?.content ?? []).map((i) => (
                          <option key={i.id} value={i.id}>{i.sku} - {localName(i, isAr)}{i.uom ? ` (${i.uom})` : ''}</option>
                        ))}
                      </NativeSelect>
                    </FormField>
                    <FormField label={t('quantity')} required error={errors[`qty${l.key}`]}>
                      <Input value={l.qty} onChange={(e) => setLine(l.key, { qty: e.target.value })} inputMode="decimal" dir="ltr" />
                    </FormField>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="mt-6"
                      aria-label={t('removeLine', { n: idx + 1 })}
                      disabled={lines.length === 1}
                      onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}
                    >
                      <Trash2 size={16} aria-hidden="true" />
                    </Button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={lines.length >= 50}
                  onClick={() => {
                    setLines((ls) => [...ls, { key: nextKey, itemId: '', qty: '' }]);
                    setNextKey((n) => n + 1);
                  }}
                >
                  <Plus size={14} aria-hidden="true" />
                  {t('addLine')}
                </Button>
              </fieldset>
              <Button type="submit" disabled={busy}>{busy ? t('saving') : t('postReceipt')}</Button>
            </form>
          </CardContent>
        </Card>
      </DataState>
    </div>
  );
}
