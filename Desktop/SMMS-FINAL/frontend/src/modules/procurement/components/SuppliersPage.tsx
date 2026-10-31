'use client';

import { useEffect, useState } from 'react';
import { Plus, Search } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog, FormField, Pagination } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { mapApiError } from '@/modules/inventory/errors';
import { can } from '@/modules/inventory/permissions';
import { Banner, DataState, localName, NativeSelect, PageHeader, useApiData } from '@/modules/inventory/shared';
import { procurementApi } from '../api';
import { procurementDict } from '../i18n';
import type { Supplier } from '../types';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function SupplierDialog({ open, onOpenChange, supplier, onSaved }: { open: boolean; onOpenChange: (o: boolean) => void; supplier?: Supplier; onSaved: () => void }) {
  const { t, dir } = useModuleT(procurementDict);
  const lang = useAuthStore((s) => s.lang);
  const [f, setF] = useState({ code: '', name: '', nameAr: '', phone: '', email: '', taxNumber: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!open) return;
    setErrors({});
    setServerError(null);
    setF({
      code: supplier?.code ?? '',
      name: supplier?.name ?? '',
      nameAr: supplier?.nameAr ?? '',
      phone: supplier?.phone ?? '',
      email: supplier?.email ?? '',
      taxNumber: supplier?.taxNumber ?? '',
    });
  }, [open, supplier]);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.code.trim()) err.code = t('errRequired');
    if (!f.name.trim()) err.name = t('errRequired');
    if (f.email.trim() && !EMAIL_RE.test(f.email.trim())) err.email = t('errEmail');
    setErrors(err);
    if (Object.keys(err).length) return;
    setBusy(true);
    setServerError(null);
    try {
      const body = {
        code: f.code.trim(),
        name: f.name.trim(),
        nameAr: f.nameAr.trim() || undefined,
        phone: f.phone.trim() || undefined,
        email: f.email.trim() || undefined,
        taxNumber: f.taxNumber.trim() || undefined,
      };
      if (supplier) await procurementApi.updateSupplier(supplier.id, body);
      else await procurementApi.createSupplier(body);
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
      <DialogContent dir={dir} className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{supplier ? t('editSupplier') : t('newSupplier')}</DialogTitle>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          <FormField label={t('code')} required error={errors.code}>
            <Input value={f.code} onChange={set('code')} dir="ltr" maxLength={40} />
          </FormField>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label={t('nameAr')}>
              <Input value={f.nameAr} onChange={set('nameAr')} maxLength={200} />
            </FormField>
            <FormField label={t('nameEn')} required error={errors.name}>
              <Input value={f.name} onChange={set('name')} dir="ltr" maxLength={200} />
            </FormField>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label={t('phone')}>
              <Input value={f.phone} onChange={set('phone')} dir="ltr" maxLength={40} />
            </FormField>
            <FormField label={t('email')} error={errors.email}>
              <Input type="email" value={f.email} onChange={set('email')} dir="ltr" maxLength={255} />
            </FormField>
          </div>
          <FormField label={t('taxNumber')}>
            <Input value={f.taxNumber} onChange={set('taxNumber')} dir="ltr" maxLength={40} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>{t('cancel')}</Button>
            <Button type="submit" disabled={busy}>{busy ? t('saving') : t('save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function SuppliersPage() {
  const { t, isAr } = useModuleT(procurementDict);
  const lang = useAuthStore((s) => s.lang);
  const role = useAuthStore((s) => s.user?.role);
  const writer = can.writeSupplier(role);
  const [q, setQ] = useState('');
  const [applied, setApplied] = useState('');
  const [active, setActive] = useState('true');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<{ open: boolean; s?: Supplier }>({ open: false });
  const [toDeactivate, setToDeactivate] = useState<Supplier | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const { data, loading, error, reload } = useApiData(
    () => procurementApi.listSuppliers({ q: applied, active, page, size: 20, sort: 'name,asc' }),
    [applied, active, page]
  );
  const rows = data?.content ?? [];

  const deactivate = async () => {
    if (!toDeactivate) return;
    setBusy(true);
    try {
      await procurementApi.deactivateSupplier(toDeactivate.id);
      setNotice({ kind: 'success', text: t('supplierDeactivated') });
      setToDeactivate(null);
      reload();
    } catch (ex) {
      setNotice({ kind: 'error', text: mapApiError(ex, lang).message });
      setToDeactivate(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <PageHeader
        title={t('suppliersTitle')}
        subtitle={data ? t('suppliersCount', { n: data.totalElements }) : undefined}
        actions={writer && (
          <Button onClick={() => setDialog({ open: true })}>
            <Plus size={16} aria-hidden="true" />
            {t('newSupplier')}
          </Button>
        )}
      />
      {notice && <Banner kind={notice.kind}>{notice.text}</Banner>}
      <Card>
        <CardContent className="p-4">
          <form
            role="search"
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              setPage(0);
              setApplied(q.trim());
            }}
          >
            <div className="relative min-w-[200px] flex-1">
              <label htmlFor="sup-q" className="sr-only">{t('search')}</label>
              <Search size={15} aria-hidden="true" className="absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <Input id="sup-q" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('searchSuppliers')} className="ps-9" />
            </div>
            <div className="w-40">
              <label htmlFor="sup-active" className="sr-only">{t('status')}</label>
              <NativeSelect id="sup-active" value={active} onChange={(e) => { setActive(e.target.value); setPage(0); }}>
                <option value="true">{t('activeOnly')}</option>
                <option value="false">{t('inactiveOnly')}</option>
                <option value="">{t('allStatuses')}</option>
              </NativeSelect>
            </div>
            <Button type="submit" variant="secondary">{t('search')}</Button>
          </form>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="p-2 md:p-4">
          <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noSuppliers')} emptyDescription={writer ? t('noSuppliersHint') : undefined}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('code')}</TableHead>
                  <TableHead>{t('name')}</TableHead>
                  <TableHead>{t('phone')}</TableHead>
                  <TableHead>{t('email')}</TableHead>
                  <TableHead>{t('taxNumber')}</TableHead>
                  <TableHead>{t('status')}</TableHead>
                  {writer && <TableHead>{t('actions')}</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((s) => (
                  <TableRow key={s.id}>
                    <TableCell dir="ltr" className="text-start font-mono text-xs">{s.code}</TableCell>
                    <TableCell>{localName(s, isAr)}</TableCell>
                    <TableCell dir="ltr" className="text-start">{s.phone ?? '-'}</TableCell>
                    <TableCell dir="ltr" className="text-start">{s.email ?? '-'}</TableCell>
                    <TableCell dir="ltr" className="text-start">{s.taxNumber ?? '-'}</TableCell>
                    <TableCell><Badge variant={s.active ? 'secondary' : 'outline'}>{s.active ? t('active') : t('inactive')}</Badge></TableCell>
                    {writer && (
                      <TableCell className="space-x-2 space-x-reverse">
                        <Button size="sm" variant="outline" onClick={() => setDialog({ open: true, s })}>{t('edit')}</Button>
                        {s.active && (
                          <Button size="sm" variant="destructive" onClick={() => setToDeactivate(s)}>{t('deactivate')}</Button>
                        )}
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
      <SupplierDialog
        open={dialog.open}
        onOpenChange={(o) => setDialog((d) => ({ ...d, open: o }))}
        supplier={dialog.s}
        onSaved={() => {
          setNotice({ kind: 'success', text: t('supplierSaved') });
          reload();
        }}
      />
      <ConfirmDialog
        open={!!toDeactivate}
        onOpenChange={(o) => !o && setToDeactivate(null)}
        title={t('deactivateTitle')}
        description={toDeactivate ? t('deactivateDesc', { name: localName(toDeactivate, isAr) }) : undefined}
        confirmLabel={t('deactivate')}
        destructive
        loading={busy}
        onConfirm={deactivate}
      />
    </div>
  );
}
