'use client';

import { useState } from 'react';
import Link from 'next/link';
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
import { Banner, DataState, fmtQty, localName, NativeSelect, PageHeader, parseQty, useItemOptions, useWarehouseOptions } from '../shared';
import { ADJUSTMENT_REASONS, type AdjustmentReason, type Movement } from '../types';

/** Stock adjustment (manager / admin only). Reason code and a note of at least 5 characters are mandatory. */
export function AdjustmentForm() {
  const { t, isAr } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const role = useAuthStore((s) => s.user?.role);
  const { keyFor, done } = useIdempotencyKey();
  const whs = useWarehouseOptions(can.correct(role));
  const [warehouseId, setWarehouseId] = useState('');
  const [itemId, setItemId] = useState('');
  const [direction, setDirection] = useState<'in' | 'out'>('in');
  const [qty, setQty] = useState('');
  const [reasonCode, setReasonCode] = useState<AdjustmentReason | ''>('');
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Movement | null>(null);

  const wh = (whs.data?.content ?? []).find((w) => w.id === warehouseId);
  const items = useItemOptions(wh?.centerId);

  if (!can.correct(role)) return <PermissionDenied />;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!warehouseId) err.warehouse = t('errRequired');
    if (!itemId) err.item = t('errRequired');
    const q = parseQty(qty);
    if (q.error) err.qty = t(q.error === 'required' ? 'errRequired' : q.error === 'decimals' ? 'errDecimals' : q.error === 'max' ? 'errMaxQty' : 'errPositive');
    if (!reasonCode) err.reasonCode = t('errRequired');
    if (note.trim().length < 5) err.note = t('errReason');
    setErrors(err);
    if (Object.keys(err).length) return;
    const body = { warehouseId, itemId, direction, quantity: q.value!, reasonCode: reasonCode as AdjustmentReason, note: note.trim() };
    setBusy(true);
    setServerError(null);
    try {
      const m = await inventoryApi.adjust(body, keyFor(body));
      done();
      setResult(m);
      setQty('');
      setNote('');
      setReasonCode('');
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <PageHeader title={t('adjustmentTitle')} subtitle={t('adjustmentHint')} actions={<Link href="/inventory" className="text-sm text-primary hover:underline">{t('backToItems')}</Link>} />
      {result && (
        <Banner kind="success">
          {t('adjustmentDone', { qty: fmtQty(result.quantity), balance: fmtQty(result.balanceAfter) })}{' '}
          <Link href="/inventory/movements" className="underline">{t('movementsTitle')}</Link>
        </Banner>
      )}
      <DataState loading={whs.loading} error={whs.error} onRetry={whs.reload}>
        <Card>
          <CardContent className="p-4">
            <form onSubmit={submit} noValidate className="space-y-4" aria-label={t('adjustmentTitle')}>
              {serverError && <Banner kind="error">{serverError}</Banner>}
              <FormField label={t('warehouse')} required error={errors.warehouse}>
                <NativeSelect value={warehouseId} onChange={(e) => { setWarehouseId(e.target.value); setItemId(''); }}>
                  <option value="">{t('choose')}</option>
                  {(whs.data?.content ?? []).map((w) => (
                    <option key={w.id} value={w.id}>{localName(w, isAr)} ({w.code})</option>
                  ))}
                </NativeSelect>
              </FormField>
              <FormField label={t('item')} required error={errors.item}>
                <NativeSelect value={itemId} onChange={(e) => setItemId(e.target.value)} disabled={!warehouseId}>
                  <option value="">{warehouseId ? t('choose') : t('chooseWarehouseFirst')}</option>
                  {(items.data?.content ?? []).map((i) => (
                    <option key={i.id} value={i.id}>{i.sku} - {localName(i, isAr)}</option>
                  ))}
                </NativeSelect>
              </FormField>
              <div className="grid gap-3 sm:grid-cols-2">
                <FormField label={t('direction')} required>
                  <NativeSelect value={direction} onChange={(e) => setDirection(e.target.value as 'in' | 'out')}>
                    <option value="in">{t('dirIn')}</option>
                    <option value="out">{t('dirOut')}</option>
                  </NativeSelect>
                </FormField>
                <FormField label={t('quantity')} required error={errors.qty}>
                  <Input value={qty} onChange={(e) => setQty(e.target.value)} inputMode="decimal" dir="ltr" />
                </FormField>
              </div>
              <FormField label={t('reasonCode')} required error={errors.reasonCode}>
                <NativeSelect value={reasonCode} onChange={(e) => setReasonCode(e.target.value as AdjustmentReason)}>
                  <option value="">{t('choose')}</option>
                  {ADJUSTMENT_REASONS.map((r) => (
                    <option key={r} value={r}>{t(`reason_${r}`)}</option>
                  ))}
                </NativeSelect>
              </FormField>
              <FormField label={t('reason')} required error={errors.note} hint={t('reasonHint')}>
                <Textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} maxLength={1000} />
              </FormField>
              <Button type="submit" disabled={busy}>{busy ? t('saving') : t('postAdjustment')}</Button>
            </form>
          </CardContent>
        </Card>
      </DataState>
    </div>
  );
}
