'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { mapApiError } from '@/modules/inventory/errors';
import { useIdempotencyKey } from '@/modules/inventory/idempotency';
import { can } from '@/modules/inventory/permissions';
import { LinesDialog, ReasonDialog } from '@/modules/inventory/components/ActionDialogs';
import { Banner, DataState, fmtMoney, fmtQty, localName, PageHeader, useApiData } from '@/modules/inventory/shared';
import { procurementApi } from '../api';
import { procurementDict } from '../i18n';
import type { PurchaseOrder } from '../types';
import { PoStatusBadge } from './PoStatusBadge';

type Dlg = null | 'approve' | 'reject' | 'cancel' | 'close' | 'receive' | 'submit';

export function PurchaseOrderDetail({ id }: { id: string }) {
  const { t, isAr, lang } = useModuleT(procurementDict);
  const user = useAuthStore((s) => s.user);
  const role = user?.role;
  const { data, loading, error, reload } = useApiData(() => procurementApi.getPo(id), [id]);
  const [po, setPo] = useState<PurchaseOrder | null>(null);
  const [dlg, setDlg] = useState<Dlg>(null);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const receiveKey = useIdempotencyKey();

  useEffect(() => {
    if (data) setPo(data);
  }, [data]);

  const run = async (fn: () => Promise<PurchaseOrder>, okKey: Parameters<typeof t>[0]) => {
    const next = await fn();
    setPo(next);
    setNotice({ kind: 'success', text: t(okKey) });
  };

  const isCreator = !!po?.createdBy && po.createdBy.id === user?.id;
  const st = po?.status;
  const actions = {
    edit: st === 'draft' && can.editPo(role),
    submit: st === 'draft' && can.editPo(role),
    decide: st === 'submitted' && can.decidePo(role) && !isCreator,
    cancel: (st === 'draft' || st === 'submitted' || st === 'approved') && can.cancelPo(role),
    receive: (st === 'approved' || st === 'partially_received') && can.receivePo(role),
    close: (st === 'approved' || st === 'partially_received') && can.closePo(role),
  };

  const doSubmit = async () => {
    if (!po) return;
    setBusy(true);
    try {
      await run(() => procurementApi.submitPo(po.id, po.version), 'doneSubmit');
      setDlg(null);
    } catch (ex) {
      setNotice({ kind: 'error', text: mapApiError(ex, lang).message });
      setDlg(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <DataState loading={loading && !po} error={po ? null : error} onRetry={reload}>
        {po && (
          <>
            <PageHeader
              title={`${t('poNumber')} ${po.poNumber}`}
              subtitle={`${localName(po.supplier, isAr)} · ${localName(po.center, isAr)}`}
              actions={
                <>
                  <Link href="/procurement/purchase-orders" className="text-sm text-primary hover:underline">{t('back')}</Link>
                  <PoStatusBadge status={po.status} />
                </>
              }
            />
            {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}
            {actions.decide === false && st === 'submitted' && isCreator && can.decidePo(role) && (
              <Banner kind="info">{t('separationOfDuties')}</Banner>
            )}
            <div className="flex flex-wrap gap-2" role="group" aria-label={t('actions')}>
              {actions.edit && (
                <Link href={`/procurement/purchase-orders/${po.id}/edit`} className="inline-flex h-8 items-center rounded-lg border px-3 text-sm hover:bg-muted max-md:min-h-10">{t('edit')}</Link>
              )}
              {actions.submit && <Button onClick={() => setDlg('submit')}>{t('submitPo')}</Button>}
              {actions.decide && <Button onClick={() => setDlg('approve')}>{t('approve')}</Button>}
              {actions.decide && <Button variant="outline" onClick={() => setDlg('reject')}>{t('reject')}</Button>}
              {actions.receive && <Button onClick={() => setDlg('receive')}>{t('receiveGoods')}</Button>}
              {actions.close && <Button variant="outline" onClick={() => setDlg('close')}>{t('closePo')}</Button>}
              {actions.cancel && <Button variant="destructive" onClick={() => setDlg('cancel')}>{t('cancelPo')}</Button>}
            </div>

            <Card>
              <CardContent className="grid gap-3 p-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
                <div><span className="text-muted-foreground">{t('warehouse')}: </span>{localName(po.warehouse, isAr)}</div>
                <div><span className="text-muted-foreground">{t('expectedDate')}: </span>{formatDate(po.expectedDate, lang)}</div>
                <div><span className="text-muted-foreground">{t('total')}: </span>{fmtMoney(po.total)}</div>
                <div><span className="text-muted-foreground">{t('createdBy')}: </span>{localName(po.createdBy, isAr)}</div>
                <div><span className="text-muted-foreground">{t('submittedAt')}: </span>{formatDate(po.submittedAt, lang, true)}</div>
                <div><span className="text-muted-foreground">{t('decidedBy')}: </span>{localName(po.approvedBy, isAr)} {po.approvedAt ? `(${formatDate(po.approvedAt, lang, true)})` : ''}</div>
                {po.decisionReason && <div className="sm:col-span-2 lg:col-span-3"><span className="text-muted-foreground">{t('decisionReason')}: </span>{po.decisionReason}</div>}
                {po.notes && <div className="sm:col-span-2 lg:col-span-3"><span className="text-muted-foreground">{t('notes')}: </span>{po.notes}</div>}
                {po.materialRequestId && (
                  <div><Link className="text-primary hover:underline" href={`/material-requests/${po.materialRequestId}`}>{t('linkedMr')}</Link></div>
                )}
                {po.workOrderId && (
                  <div><Link className="text-primary hover:underline" href={`/work-orders/${po.workOrderId}`}>{t('linkedWo')}</Link></div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader><CardTitle>{t('lines')}</CardTitle></CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>#</TableHead>
                      <TableHead>{t('item')}</TableHead>
                      <TableHead>{t('qtyOrdered')}</TableHead>
                      <TableHead>{t('qtyReceived')}</TableHead>
                      <TableHead>{t('qtyRemaining')}</TableHead>
                      <TableHead>{t('unitPrice')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {po.lines.map((l) => (
                      <TableRow key={l.id}>
                        <TableCell>{l.lineNo}</TableCell>
                        <TableCell>{l.item.sku} - {localName(l.item, isAr)}</TableCell>
                        <TableCell>{fmtQty(l.qtyOrdered)}</TableCell>
                        <TableCell>{fmtQty(l.qtyReceived)}</TableCell>
                        <TableCell>{fmtQty(Math.max(0, l.qtyOrdered - l.qtyReceived))}</TableCell>
                        <TableCell dir="ltr" className="text-start">{fmtMoney(l.unitPrice)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>

            <Card>
              <CardHeader><CardTitle>{t('receipts')}</CardTitle></CardHeader>
              <CardContent>
                {po.receipts.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t('noReceipts')}</p>
                ) : (
                  <ul className="space-y-2 text-sm">
                    {po.receipts.map((r) => (
                      <li key={r.id} className="rounded-md border p-2">
                        <div className="font-medium">{formatDate(r.receivedAt, lang, true)} · {localName(r.receivedBy, isAr)}</div>
                        {r.note && <div className="text-muted-foreground">{r.note}</div>}
                        <ul className="mt-1 list-disc ps-5">
                          {r.lines.map((rl) => {
                            const line = po.lines.find((x) => x.id === rl.poLineId);
                            return <li key={rl.poLineId + (rl.movementId ?? '')}>{line ? localName(line.item, isAr) : rl.poLineId}: {fmtQty(rl.quantity)}</li>;
                          })}
                        </ul>
                      </li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>

            <ConfirmDialog
              open={dlg === 'submit'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('submitPo')}
              description={t('submitConfirm')}
              confirmLabel={t('submitPo')}
              loading={busy}
              onConfirm={doSubmit}
            />
            <ReasonDialog
              open={dlg === 'approve'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('approve')}
              description={t('approveDesc')}
              label={t('noteOptional')}
              submitLabel={t('approve')}
              optional
              onSubmit={(note) => run(() => procurementApi.approvePo(po.id, note || undefined, po.version), 'doneApprove')}
            />
            <ReasonDialog
              open={dlg === 'reject'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('reject')}
              label={t('reason')}
              submitLabel={t('reject')}
              destructive
              onSubmit={(r) => run(() => procurementApi.rejectPo(po.id, r, po.version), 'doneReject')}
            />
            <ReasonDialog
              open={dlg === 'cancel'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('cancelPo')}
              label={t('reason')}
              submitLabel={t('cancelPo')}
              destructive
              onSubmit={(r) => run(() => procurementApi.cancelPo(po.id, r, po.version), 'doneCancel')}
            />
            <ReasonDialog
              open={dlg === 'close'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('closePo')}
              description={t('closeDesc')}
              label={t('reason')}
              submitLabel={t('closePo')}
              onSubmit={(r) => run(() => procurementApi.closePo(po.id, r, po.version), 'doneClose')}
            />
            <LinesDialog
              open={dlg === 'receive'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('receiveGoods')}
              description={t('receiveDesc')}
              submitLabel={t('receiveGoods')}
              enforceMax={false}
              extra={{ label: t('noteOptional') }}
              lines={po.lines.map((l) => {
                const remaining = Math.max(0, l.qtyOrdered - l.qtyReceived);
                return {
                  id: l.id,
                  label: `${l.item.sku} - ${localName(l.item, isAr)}`,
                  hint: t('remainingHint', { n: fmtQty(remaining) }),
                  max: remaining,
                  defaultValue: remaining > 0 ? remaining : undefined,
                };
              })}
              onSubmit={async (ls, note) => {
                const body = { note: note || undefined, lines: ls.map((x) => ({ poLineId: x.id, quantity: x.quantity })) };
                await procurementApi.receivePo(po.id, body, receiveKey.keyFor({ id: po.id, ...body }));
                receiveKey.done();
                setNotice({ kind: 'success', text: t('doneReceive') });
                reload();
              }}
            />
          </>
        )}
      </DataState>
    </div>
  );
}
