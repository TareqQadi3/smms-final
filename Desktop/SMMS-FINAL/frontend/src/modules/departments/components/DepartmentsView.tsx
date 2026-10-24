'use client';

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { ApiErrorView } from '@/components/assets/ApiErrorView';
import { SELECT_CLASS } from '@/components/assets/styles';
import { ConfirmDialog, EmptyState, FormField, LoadingState, Pagination, PermissionDenied } from '@/components/common';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { useCenters } from '@/modules/assets/hooks';
import { apiError, useLoad } from '@/modules/assets/lib';
import { DEPT_READ, DEPT_WRITE, hasRole } from '@/modules/assets/roles';
import { departmentsApi } from '../api';
import { deptDict } from '../i18n';
import type { Department, DepartmentFormValues, UserLite } from '../types';

const EMPTY: DepartmentFormValues = { centerId: '', name: '', nameAr: '', code: '', active: true };
type Errors = Partial<Record<keyof DepartmentFormValues, string>>;

function MembersPanel({ dept, canWrite, onClose }: { dept: Department; canWrite: boolean; onClose: () => void }) {
  const { t, lang } = useModuleT(deptDict);
  const [page, setPage] = useState(0);
  const fetcher = useCallback(() => departmentsApi.members(dept.id, page), [dept.id, page]);
  const { data, error, loading, reload } = useLoad(fetcher);
  const candFetcher = useCallback(
    () => (dept.center ? departmentsApi.candidateUsers(dept.center.id) : Promise.resolve(null)),
    [dept.center]
  );
  const cands = useLoad(candFetcher, canWrite && !!dept.center);
  const [userId, setUserId] = useState('');
  const [userError, setUserError] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const memberIds = new Set((data?.content ?? []).map((m) => m.id));
  const candidates: UserLite[] = (cands.data?.content ?? []).filter((u) => u.departmentId !== dept.id && !memberIds.has(u.id));

  const assign = async (e: FormEvent) => {
    e.preventDefault();
    if (!userId) {
      setUserError(t('vUser'));
      return;
    }
    setUserError('');
    setBusy(true);
    setMsg(null);
    try {
      await departmentsApi.linkUser(userId, dept.id);
      setUserId('');
      setMsg({ kind: 'ok', text: t('assignedOk') });
      reload();
      cands.reload();
    } catch (err) {
      setMsg({ kind: 'err', text: apiError(err).message || t('saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  const unlink = async (id: string) => {
    setBusy(true);
    setMsg(null);
    try {
      await departmentsApi.linkUser(id, null);
      setMsg({ kind: 'ok', text: t('removedOk') });
      reload();
      cands.reload();
    } catch (err) {
      setMsg({ kind: 'err', text: apiError(err).message || t('saveFailed') });
    } finally {
      setBusy(false);
    }
  };

  const deptName = lang === 'ar' ? dept.nameAr : dept.name;
  return (
    <section aria-labelledby="dept-members" className="space-y-3 rounded-lg border p-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="dept-members" className="text-lg font-semibold">{t('membersOf', { name: deptName })}</h2>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>{t('closeMembers')}</Button>
      </div>
      <div aria-live="polite">
        {msg && <p role={msg.kind === 'err' ? 'alert' : 'status'} className={msg.kind === 'err' ? 'text-sm text-destructive' : 'text-sm text-green-700 dark:text-green-500'}>{msg.text}</p>}
      </div>
      {canWrite && dept.active && (
        <form onSubmit={assign} noValidate className="flex flex-wrap items-end gap-3" aria-label={t('assignUser')}>
          <FormField label={t('assignUser')} error={userError} hint={cands.data && candidates.length === 0 ? t('noCandidates') : undefined}>
            <select className={SELECT_CLASS} value={userId} onChange={(e) => setUserId(e.target.value)} disabled={busy}>
              <option value="">{t('selectUser')}</option>
              {candidates.map((u) => (
                <option key={u.id} value={u.id}>{lang === 'ar' ? u.fullNameAr || u.fullName : u.fullName}{u.departmentName ? ` (${u.departmentName})` : ''}</option>
              ))}
            </select>
          </FormField>
          <Button type="submit" disabled={busy}>{t('assign')}</Button>
        </form>
      )}
      {loading && <LoadingState />}
      {!loading && error && <ApiErrorView error={error} onRetry={reload} />}
      {!loading && !error && data && data.content.length === 0 && <EmptyState title={t('noMembers')} />}
      {!loading && !error && data && data.content.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">{t('members')}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t('memberName')}</TableHead>
                  <TableHead scope="col">{t('role')}</TableHead>
                  {canWrite && <TableHead scope="col"><span className="sr-only">{t('actions')}</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((m) => (
                  <TableRow key={m.id}>
                    <TableCell>{lang === 'ar' ? m.fullNameAr || m.fullName : m.fullName}</TableCell>
                    <TableCell>{m.role}</TableCell>
                    {canWrite && (
                      <TableCell>
                        <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => unlink(m.id)}>{t('remove')}</Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
        </>
      )}
    </section>
  );
}

export function DepartmentsView() {
  const { t, lang } = useModuleT(deptDict);
  const role = useAuthStore((s) => s.user?.role);
  const allowed = hasRole(role, DEPT_READ);
  const canWrite = hasRole(role, DEPT_WRITE);
  const centers = useCenters();
  const [draft, setDraft] = useState({ q: '', centerId: '', active: '' });
  const [applied, setApplied] = useState(draft);
  const [page, setPage] = useState(0);
  const fetcher = useCallback(
    () =>
      departmentsApi.list({
        q: applied.q.trim(), centerId: applied.centerId, active: applied.active === '' ? '' : applied.active === 'true', page, size: 20,
      }),
    [applied, page]
  );
  const { data, error, loading, reload } = useLoad(fetcher, allowed);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Department | null>(null);
  const [values, setValues] = useState<DepartmentFormValues>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [toDeactivate, setToDeactivate] = useState<Department | null>(null);
  const [deactivating, setDeactivating] = useState(false);
  const [membersOf, setMembersOf] = useState<Department | null>(null);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const centerList = useMemo(() => centers.data ?? [], [centers.data]);

  useEffect(() => {
    if (formOpen && !editing && centerList.length === 1 && !values.centerId) setValues((v) => ({ ...v, centerId: centerList[0].id }));
  }, [formOpen, editing, centerList, values.centerId]);

  if (!allowed) return <PermissionDenied />;

  const openCreate = () => {
    setEditing(null);
    setValues({ ...EMPTY, centerId: centerList.length === 1 ? centerList[0].id : '' });
    setErrors({});
    setFormError('');
    setFormOpen(true);
  };
  const openEdit = (d: Department) => {
    setEditing(d);
    setValues({ centerId: d.center?.id ?? '', name: d.name, nameAr: d.nameAr, code: d.code, active: d.active });
    setErrors({});
    setFormError('');
    setFormOpen(true);
  };

  const validate = (): Errors => {
    const e: Errors = {};
    if (!values.centerId) e.centerId = t('vCenter');
    if (!values.name.trim()) e.name = t('vName');
    else if (values.name.trim().length > 200) e.name = t('vTooLong', { n: 200 });
    if (!values.nameAr.trim()) e.nameAr = t('vNameAr');
    else if (values.nameAr.trim().length > 200) e.nameAr = t('vTooLong', { n: 200 });
    if (!values.code.trim()) e.code = t('vCode');
    else if (values.code.trim().length > 40) e.code = t('vTooLong', { n: 40 });
    return e;
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    const errs = validate();
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    setFormError('');
    try {
      if (editing) await departmentsApi.update(editing.id, values);
      else await departmentsApi.create(values);
      setFormOpen(false);
      setMsg({ kind: 'ok', text: editing ? t('updatedOk') : t('createdOk') });
      reload();
    } catch (err) {
      setFormError(apiError(err).message || t('saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const deactivate = async () => {
    if (!toDeactivate) return;
    setDeactivating(true);
    try {
      await departmentsApi.deactivate(toDeactivate.id);
      setToDeactivate(null);
      setMsg({ kind: 'ok', text: t('deactivatedOk') });
      reload();
    } catch (err) {
      setToDeactivate(null);
      setMsg({ kind: 'err', text: apiError(err).message || t('saveFailed') });
    } finally {
      setDeactivating(false);
    }
  };

  const submitFilter = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setApplied(draft);
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t('title')}</h1>
          {data && <p className="text-sm text-muted-foreground">{t('subtitle', { n: data.totalElements })}</p>}
          {!canWrite && <p className="text-xs text-muted-foreground">{t('readOnly')}</p>}
        </div>
        {canWrite && <Button type="button" onClick={openCreate}>{t('add')}</Button>}
      </div>

      <form onSubmit={submitFilter} role="search" aria-label={t('list')} className="grid gap-3 rounded-lg border p-3 sm:grid-cols-2 lg:grid-cols-4">
        <FormField label={t('search')}>
          <Input type="search" value={draft.q} onChange={(e) => setDraft({ ...draft, q: e.target.value })} />
        </FormField>
        {centerList.length !== 1 && (
          <FormField label={t('center')}>
            <select className={SELECT_CLASS} value={draft.centerId} onChange={(e) => setDraft({ ...draft, centerId: e.target.value })}>
              <option value="">{t('allCenters')}</option>
              {centerList.map((c) => <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>)}
            </select>
          </FormField>
        )}
        <FormField label={t('status')}>
          <select className={SELECT_CLASS} value={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.value })}>
            <option value="">{t('all')}</option>
            <option value="true">{t('activeOnly')}</option>
            <option value="false">{t('inactiveOnly')}</option>
          </select>
        </FormField>
        <div className="flex items-end"><Button type="submit" className="w-full">{t('filter')}</Button></div>
      </form>

      <div aria-live="polite">
        {msg && <p role={msg.kind === 'err' ? 'alert' : 'status'} className={msg.kind === 'err' ? 'text-sm text-destructive' : 'text-sm text-green-700 dark:text-green-500'}>{msg.text}</p>}
        {loading && <LoadingState />}
        {!loading && error && <ApiErrorView error={error} onRetry={reload} />}
        {!loading && !error && data && data.content.length === 0 && <EmptyState title={t('emptyTitle')} description={t('emptyBody')} />}
      </div>

      {!loading && !error && data && data.content.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <caption className="sr-only">{t('list')}</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">{t('code')}</TableHead>
                  <TableHead scope="col">{t('name')}</TableHead>
                  <TableHead scope="col">{t('center')}</TableHead>
                  <TableHead scope="col">{t('status')}</TableHead>
                  <TableHead scope="col"><span className="sr-only">{t('actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((d) => (
                  <TableRow key={d.id}>
                    <TableCell className="font-mono text-xs">{d.code}</TableCell>
                    <TableCell>{lang === 'ar' ? d.nameAr : d.name}</TableCell>
                    <TableCell>{lang === 'ar' ? d.center?.nameAr : d.center?.name}</TableCell>
                    <TableCell><Badge variant={d.active ? 'secondary' : 'outline'}>{d.active ? t('active') : t('inactive')}</Badge></TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" size="sm" variant="outline" onClick={() => setMembersOf(d)}>{t('members')}</Button>
                        {canWrite && <Button type="button" size="sm" variant="outline" onClick={() => openEdit(d)}>{t('edit')}</Button>}
                        {canWrite && d.active && <Button type="button" size="sm" variant="destructive" onClick={() => setToDeactivate(d)}>{t('deactivate')}</Button>}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} />
        </>
      )}

      {membersOf && <MembersPanel key={membersOf.id} dept={membersOf} canWrite={canWrite} onClose={() => setMembersOf(null)} />}

      <Dialog open={formOpen} onOpenChange={(o) => !saving && setFormOpen(o)}>
        <DialogContent dir={lang === 'ar' ? 'rtl' : 'ltr'}>
          <form onSubmit={save} noValidate className="space-y-4">
            <DialogHeader><DialogTitle>{editing ? t('editTitle') : t('createTitle')}</DialogTitle></DialogHeader>
            <FormField label={t('center')} required error={errors.centerId}>
              <select className={SELECT_CLASS} value={values.centerId} disabled={saving} onChange={(e) => setValues({ ...values, centerId: e.target.value })}>
                <option value="">{t('selectCenter')}</option>
                {centerList.map((c) => <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>)}
              </select>
            </FormField>
            <FormField label={t('nameAr')} required error={errors.nameAr}>
              <Input value={values.nameAr} disabled={saving} onChange={(e) => setValues({ ...values, nameAr: e.target.value })} />
            </FormField>
            <FormField label={t('name')} required error={errors.name}>
              <Input value={values.name} disabled={saving} onChange={(e) => setValues({ ...values, name: e.target.value })} />
            </FormField>
            <FormField label={t('code')} required error={errors.code}>
              <Input value={values.code} disabled={saving} onChange={(e) => setValues({ ...values, code: e.target.value })} />
            </FormField>
            {editing && (
              <label className="flex min-h-10 items-center gap-2 text-sm">
                <input type="checkbox" className="size-4" checked={values.active} disabled={saving} onChange={(e) => setValues({ ...values, active: e.target.checked })} />
                {t('active')}
              </label>
            )}
            {formError && <p role="alert" className="text-sm text-destructive">{formError}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={saving} onClick={() => setFormOpen(false)}>{t('cancel')}</Button>
              <Button type="submit" disabled={saving}>{saving ? t('saving') : t('save')}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={toDeactivate !== null}
        onOpenChange={(o) => !o && setToDeactivate(null)}
        title={t('confirmDeactivate')}
        description={t('confirmDeactivateBody')}
        confirmLabel={t('deactivate')}
        destructive
        loading={deactivating}
        onConfirm={deactivate}
      />
    </div>
  );
}
