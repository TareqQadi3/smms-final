'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog, FormField } from '@/components/common';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { mapApiError } from '@/modules/inventory/errors';
import { useIdempotencyKey } from '@/modules/inventory/idempotency';
import { can } from '@/modules/inventory/permissions';
import { LinesDialog, ReasonDialog } from '@/modules/inventory/components/ActionDialogs';
import { Banner, DataState, fmtQty, localName, NativeSelect, PageHeader, useApiData } from '@/modules/inventory/shared';
import { procurementApi } from '@/modules/procurement/api';
import { materialsApi } from '../api';
import { materialsDict } from '../i18n';
import type { MaterialRequest, MrLine } from '../types';
import { MrStatusBadge } from './MrStatusBadge';

type Dlg = null | 'approve' | 'reject' | 'reserve' | 'issue' | 'consume' | 'return' | 'close' | 'cancel' | 'po';

const outstandingIssue = (l: MrLine) => Math.max(0, l.qtyApproved - l.qtyIssued);
const inHand = (l: MrLine) => Math.max(0, l.qtyIssued - l.qtyConsumed - l.qtyReturned);

function SupplierPoDialog({ open, onOpenChange, mrId, onCreated }: { open: boolean; onOpenChange: (o: boolean) => void; mrId: string; onCreated: (poId: string) => void }) {
  const { t, dir, isAr } = useModuleT(materialsDict);
  const lang = useAuthStore((s) => s.lang);
  const suppliers = useApiData(() => procurementApi.listSuppliers({ active: true, size: 200, sort: 'name,asc' }), [], open);
  const [supplierId, setSupplierId] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (open) {
      setSupplierId('');
      setError(undefined);
      setServerError(null);
    }
  }, [open]);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!supplierId) return setError(t('errRequired'));
    setError(undefined);
    setBusy(true);
    setServerError(null);
    try {
      const po = await materialsApi.createPurchaseOrder(mrId, supplierId);
      onCreated(po.id);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir}>
        <DialogHeader>
          <DialogTitle>{t('createPo')}</DialogTitle>
          <DialogDescription>{t('createPoDesc')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          {suppliers.error && <Banner kind="error">{suppliers.error.message}</Banner>}
          <FormField label={t('supplier')} required error={error}>
            <NativeSelect value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
              <option value="">{t('choose')}</option>
              {(suppliers.data?.content ?? []).map((s) => (
                <option key={s.id} value={s.id}>{localName(s, isAr)} ({s.code})</option>
              ))}
            </NativeSelect>
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>{t('cancel')}</Button>
            <Button type="submit" disabled={busy}>{busy ? t('working') : t('createPo')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function MaterialRequestDetail({ id }: { id: string }) {
  const { t, isAr, lang } = useModuleT(materialsDict);
  const user = useAuthStore((s) => s.user);
  const role = user?.role;
  const router = useRouter();
  const { data, loading, error, reload } = useApiData(() => materialsApi.get(id), [id]);
  const [mr, setMr] = useState<MaterialRequest | null>(null);
  const [dlg, setDlg] = useState<Dlg>(null);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const kReserve = useIdempotencyKey();
  const kIssue = useIdempotencyKey();
  const kConsume = useIdempotencyKey();
  const kReturn = useIdempotencyKey();

  useEffect(() => {
    if (data) setMr(data);
  }, [data]);

  const ok = (next: MaterialRequest, key: Parameters<typeof t>[0]) => {
    setMr(next);
    setNotice({ kind: 'success', text: t(key) });
  };

  const st = mr?.status;
  const mine = !!mr?.requestedBy && mr.requestedBy.id === user?.id;
  const anyOutstanding = !!mr && mr.lines.some((l) => outstandingIssue(l) > 0);
  const anyInHand = !!mr && mr.lines.some((l) => inHand(l) > 0);
  const can_ = {
    approve: st === 'submitted' && can.decideMr(role),
    reserve: st === 'approved' && can.warehouseMr(role),
    issue: (st === 'approved' || st === 'reserved' || st === 'issued') && anyOutstanding && can.warehouseMr(role),
    consume: st === 'issued' && anyInHand && can.consumeMr(role),
    giveBack: st === 'issued' && anyInHand && can.warehouseMr(role),
    close: st === 'issued' && can.closeMr(role),
    cancel:
      (st === 'submitted' || st === 'approved' || st === 'reserved') &&
      can.cancelMr(role) &&
      // a requester (technician / control room) may cancel only their own request while it is submitted
      (role === 'technician' || role === 'control_room' ? st === 'submitted' && mine : true),
    po: st === 'approved' && can.closeMr(role),
  };

  const reserve = async () => {
    if (!mr) return;
    setBusy(true);
    try {
      const next = await materialsApi.reserve(mr.id, mr.version, kReserve.keyFor({ id: mr.id, v: mr.version }));
      kReserve.done();
      ok(next, 'doneReserve');
    } catch (ex) {
      setNotice({ kind: 'error', text: mapApiError(ex, lang).message });
    } finally {
      setBusy(false);
      setDlg(null);
    }
  };
  const close = async () => {
    if (!mr) return;
    setBusy(true);
    try {
      ok(await materialsApi.close(mr.id, mr.version), 'doneClose');
    } catch (ex) {
      setNotice({ kind: 'error', text: mapApiError(ex, lang).message });
    } finally {
      setBusy(false);
      setDlg(null);
    }
  };

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <DataState loading={loading && !mr} error={mr ? null : error} onRetry={reload}>
        {mr && (
          <>
            <PageHeader
              title={`${t('requestNumber')} ${mr.requestNumber}`}
              subtitle={`${localName(mr.warehouse, isAr)} · ${localName(mr.center, isAr)}`}
              actions={
                <>
                  <Link href="/material-requests" className="text-sm text-primary hover:underline">{t('back')}</Link>
                  <MrStatusBadge status={mr.status} />
                </>
              }
            />
            {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}
            <div className="flex flex-wrap gap-2" role="group" aria-label={t('actions')}>
              {can_.approve && <Button onClick={() => setDlg('approve')}>{t('approve')}</Button>}
              {can_.approve && <Button variant="outline" onClick={() => setDlg('reject')}>{t('reject')}</Button>}
              {can_.reserve && <Button onClick={() => setDlg('reserve')}>{t('reserve')}</Button>}
              {can_.issue && <Button onClick={() => setDlg('issue')}>{t('issue')}</Button>}
              {can_.consume && <Button onClick={() => setDlg('consume')}>{t('consume')}</Button>}
              {can_.giveBack && <Button variant="outline" onClick={() => setDlg('return')}>{t('giveBack')}</Button>}
              {can_.close && <Button variant="outline" onClick={() => setDlg('close')}>{t('closeMr')}</Button>}
              {can_.po && <Button variant="outline" onClick={() => setDlg('po')}>{t('createPo')}</Button>}
              {can_.cancel && <Button variant="destructive" onClick={() => setDlg('cancel')}>{t('cancelMr')}</Button>}
            </div>

            <Card>
              <CardContent className="grid gap-3 p-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
                <div>
                  <span className="text-muted-foreground">{t('workOrder')}: </span>
                  {mr.workOrder ? <Link className="text-primary hover:underline" href={`/work-orders/${mr.workOrder.id}`}>{mr.workOrder.ticketNumber}</Link> : '-'}
                </div>
                <div><span className="text-muted-foreground">{t('requestedBy')}: </span>{localName(mr.requestedBy, isAr)}</div>
                <div><span className="text-muted-foreground">{t('createdAt')}: </span>{formatDate(mr.createdAt, lang, true)}</div>
                {mr.decidedBy && <div><span className="text-muted-foreground">{t('decidedBy')}: </span>{localName(mr.decidedBy, isAr)} ({formatDate(mr.decidedAt, lang, true)})</div>}
                {mr.decisionNote && <div className="sm:col-span-2"><span className="text-muted-foreground">{t('decisionNote')}: </span>{mr.decisionNote}</div>}
                {mr.note && <div className="sm:col-span-2"><span className="text-muted-foreground">{t('note')}: </span>{mr.note}</div>}
                {mr.cancelReason && <div className="sm:col-span-2"><span className="text-muted-foreground">{t('cancelReason')}: </span>{mr.cancelReason}</div>}
              </CardContent>
            </Card>

            <Card>
              <CardHeader><CardTitle>{t('lines')}</CardTitle></CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('item')}</TableHead>
                      <TableHead>{t('qtyRequested')}</TableHead>
                      <TableHead>{t('qtyApproved')}</TableHead>
                      <TableHead>{t('qtyReserved')}</TableHead>
                      <TableHead>{t('qtyIssued')}</TableHead>
                      <TableHead>{t('qtyConsumed')}</TableHead>
                      <TableHead>{t('qtyReturned')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {mr.lines.map((l) => (
                      <TableRow key={l.id}>
                        <TableCell>{l.item.sku} - {localName(l.item, isAr)}</TableCell>
                        <TableCell>{fmtQty(l.qtyRequested)}</TableCell>
                        <TableCell>{fmtQty(l.qtyApproved)}</TableCell>
                        <TableCell>{fmtQty(l.qtyReserved)}</TableCell>
                        <TableCell>{fmtQty(l.qtyIssued)}</TableCell>
                        <TableCell>{fmtQty(l.qtyConsumed)}</TableCell>
                        <TableCell>{fmtQty(l.qtyReturned)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>

            <LinesDialog
              open={dlg === 'approve'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('approve')}
              description={t('approveDesc')}
              submitLabel={t('approve')}
              includeZero
              extra={{ label: t('noteOptional') }}
              lines={mr.lines.map((l) => ({ id: l.id, label: `${l.item.sku} - ${localName(l.item, isAr)}`, max: l.qtyRequested, defaultValue: l.qtyRequested }))}
              onSubmit={async (ls, note) => {
                // Server rule: when lines are given, unlisted lines are approved with 0.
                const next = await materialsApi.approve(mr.id, { note: note || undefined, lines: ls.map((x) => ({ lineId: x.id, approvedQuantity: x.quantity })), version: mr.version });
                ok(next, 'doneApprove');
              }}
            />
            <ReasonDialog
              open={dlg === 'reject'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('reject')}
              label={t('reason')}
              submitLabel={t('reject')}
              destructive
              onSubmit={async (r) => ok(await materialsApi.reject(mr.id, r, mr.version), 'doneReject')}
            />
            <ConfirmDialog
              open={dlg === 'reserve'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('reserve')}
              description={t('reserveDesc')}
              confirmLabel={t('reserve')}
              loading={busy}
              onConfirm={reserve}
            />
            <LinesDialog
              open={dlg === 'issue'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('issue')}
              description={t('issueDesc')}
              submitLabel={t('issue')}
              lines={mr.lines.filter((l) => outstandingIssue(l) > 0).map((l) => ({ id: l.id, label: `${l.item.sku} - ${localName(l.item, isAr)}`, max: outstandingIssue(l), defaultValue: outstandingIssue(l) }))}
              onSubmit={async (ls) => {
                const lines = ls.map((x) => ({ lineId: x.id, quantity: x.quantity }));
                const next = await materialsApi.issue(mr.id, lines, mr.version, kIssue.keyFor({ id: mr.id, lines, v: mr.version }));
                kIssue.done();
                ok(next, 'doneIssue');
              }}
            />
            <LinesDialog
              open={dlg === 'consume'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('consume')}
              description={t('consumeDesc')}
              submitLabel={t('consume')}
              extra={{ label: t('noteOptional') }}
              lines={mr.lines.filter((l) => inHand(l) > 0).map((l) => ({ id: l.id, label: `${l.item.sku} - ${localName(l.item, isAr)}`, max: inHand(l), defaultValue: inHand(l) }))}
              onSubmit={async (ls, note) => {
                const lines = ls.map((x) => ({ lineId: x.id, quantity: x.quantity }));
                const next = await materialsApi.consume(mr.id, lines, note || undefined, mr.version, kConsume.keyFor({ id: mr.id, lines, note, v: mr.version }));
                kConsume.done();
                ok(next, 'doneConsume');
              }}
            />
            <LinesDialog
              open={dlg === 'return'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('giveBack')}
              description={t('returnDesc')}
              submitLabel={t('giveBack')}
              extra={{ label: t('reason'), required: true, minLength: 5 }}
              lines={mr.lines.filter((l) => inHand(l) > 0).map((l) => ({ id: l.id, label: `${l.item.sku} - ${localName(l.item, isAr)}`, max: inHand(l) }))}
              onSubmit={async (ls, reason) => {
                const lines = ls.map((x) => ({ lineId: x.id, quantity: x.quantity }));
                const next = await materialsApi.giveBack(mr.id, lines, reason, mr.version, kReturn.keyFor({ id: mr.id, lines, reason, v: mr.version }));
                kReturn.done();
                ok(next, 'doneReturn');
              }}
            />
            <ConfirmDialog
              open={dlg === 'close'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('closeMr')}
              description={t('closeDesc')}
              confirmLabel={t('closeMr')}
              loading={busy}
              onConfirm={close}
            />
            <ReasonDialog
              open={dlg === 'cancel'}
              onOpenChange={(o) => !o && setDlg(null)}
              title={t('cancelMr')}
              label={t('reason')}
              submitLabel={t('cancelMr')}
              destructive
              onSubmit={async (r) => ok(await materialsApi.cancel(mr.id, r, mr.version), 'doneCancel')}
            />
            <SupplierPoDialog open={dlg === 'po'} onOpenChange={(o) => !o && setDlg(null)} mrId={mr.id} onCreated={(poId) => router.push(`/procurement/purchase-orders/${poId}`)} />
          </>
        )}
      </DataState>
    </div>
  );
}
