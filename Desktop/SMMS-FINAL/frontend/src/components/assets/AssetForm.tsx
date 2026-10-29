'use client';

import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { FormField } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useModuleT } from '@/lib/module-i18n';
import { assetsApi } from '@/modules/assets/api';
import { useCenters } from '@/modules/assets/hooks';
import { assetsDict } from '@/modules/assets/i18n';
import { apiError } from '@/modules/assets/lib';
import { ASSET_STATUSES, CRITICALITIES, enumDict, enumLabel } from '@/modules/assets/enums';
import { departmentsApi } from '@/modules/departments/api';
import type { Department } from '@/modules/departments/types';
import { validateAsset, type AssetErrors } from '@/modules/assets/validation';
import type { Asset, AssetFormValues } from '@/modules/assets/types';
import { SELECT_CLASS } from './styles';

const EMPTY: AssetFormValues = {
  centerId: '', departmentId: '', name: '', nameAr: '', assetCode: '', category: '', manufacturer: '', model: '',
  serialNumber: '', location: '', installDate: '', warrantyExpiry: '', criticality: '', status: 'active',
};

function fromAsset(a: Asset): AssetFormValues {
  return {
    centerId: a.center?.id ?? '',
    departmentId: a.department?.id ?? '',
    name: a.name ?? '',
    nameAr: a.nameAr ?? '',
    assetCode: a.assetCode ?? '',
    category: a.category ?? '',
    manufacturer: a.manufacturer ?? '',
    model: a.model ?? '',
    serialNumber: a.serialNumber ?? '',
    location: a.location ?? '',
    installDate: a.installDate ?? '',
    warrantyExpiry: a.warrantyExpiry ?? '',
    criticality: a.criticality ?? '',
    status: a.status ?? 'active',
  };
}

/** Create / edit form of one asset. Edit sends the loaded `version`; a 409 asks the user to reload. */
export function AssetForm({
  mode,
  asset,
  onSaved,
  onCancel,
}: {
  mode: 'create' | 'edit';
  asset?: Asset;
  onSaved: (a: Asset) => void;
  onCancel: () => void;
}) {
  const { t, lang } = useModuleT(assetsDict);
  const { t: te } = useModuleT(enumDict);
  const centers = useCenters();
  const [values, setValues] = useState<AssetFormValues>(asset ? fromAsset(asset) : EMPTY);
  const [version, setVersion] = useState<number>(asset?.version ?? 0);
  const [errors, setErrors] = useState<AssetErrors>({});
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [info, setInfo] = useState('');
  const [departments, setDepartments] = useState<Department[]>([]);
  const firstErrorRef = useRef<HTMLFormElement>(null);

  const set = <K extends keyof AssetFormValues>(k: K, v: AssetFormValues[K]) => setValues((s) => ({ ...s, [k]: v }));
  const centerList = useMemo(() => centers.data ?? [], [centers.data]);

  // A single visible center is selected for the user.
  useEffect(() => {
    if (mode === 'create' && centerList.length === 1 && !values.centerId) set('centerId', centerList[0].id);
  }, [mode, centerList, values.centerId]);

  useEffect(() => {
    if (!values.centerId) {
      setDepartments([]);
      return;
    }
    let cancelled = false;
    departmentsApi
      .list({ centerId: values.centerId, active: true, size: 100 })
      .then((p) => !cancelled && setDepartments(p.content))
      .catch(() => !cancelled && setDepartments([]));
    return () => {
      cancelled = true;
    };
  }, [values.centerId]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    setInfo('');
    const errs = validateAsset(values, mode, t);
    setErrors(errs);
    if (Object.keys(errs).length > 0) {
      requestAnimationFrame(() => firstErrorRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
      return;
    }
    setSaving(true);
    try {
      const saved = mode === 'create' ? await assetsApi.create(values) : await assetsApi.update(asset!.id, values, version);
      onSaved(saved);
    } catch (err) {
      const { status, message } = apiError(err);
      if (status === 409 && mode === 'edit') setConflict(true);
      else if (status === 403) setFormError(lang === 'ar' ? 'ليس لديك صلاحية لهذا الإجراء.' : 'You are not allowed to do this.');
      else setFormError(message || t('saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const reload = async () => {
    if (!asset) return;
    try {
      const fresh = await assetsApi.get(asset.id);
      setValues(fromAsset(fresh));
      setVersion(fresh.version);
      setConflict(false);
      setErrors({});
      setInfo(t('reloaded'));
    } catch (err) {
      setFormError(apiError(err).message || t('loadFailed'));
    }
  };

  const text = (k: keyof AssetFormValues, label: string, required = false, extra?: { type?: string; max?: string }) => (
    <FormField label={label} required={required} error={errors[k]}>
      <Input
        type={extra?.type ?? 'text'}
        max={extra?.max}
        value={String(values[k])}
        onChange={(e) => set(k, e.target.value as never)}
        disabled={saving}
      />
    </FormField>
  );

  return (
    <form ref={firstErrorRef} onSubmit={submit} noValidate className="space-y-5" aria-label={mode === 'create' ? t('createTitle') : t('editTitle')}>
      {conflict && (
        <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 space-y-2">
          <p>{t('conflictReload')}</p>
          <Button type="button" variant="outline" size="sm" onClick={reload}>{t('reload')}</Button>
        </div>
      )}
      {formError && <p role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{formError}</p>}
      {info && <p role="status" className="text-sm text-green-700 dark:text-green-500">{info}</p>}

      <div className="grid gap-4 sm:grid-cols-2">
        <FormField
          label={t('center')}
          required={mode === 'create'}
          error={errors.centerId}
          hint={mode === 'edit' ? t('centerLocked') : undefined}
        >
          <select
            className={SELECT_CLASS}
            value={values.centerId}
            disabled={saving || mode === 'edit'}
            onChange={(e) => {
              set('centerId', e.target.value);
              set('departmentId', '');
            }}
          >
            <option value="">{t('selectCenter')}</option>
            {(mode === 'edit' && asset && !centerList.some((c) => c.id === asset.center.id) ? [asset.center, ...centerList] : centerList).map((c) => (
              <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>
            ))}
          </select>
        </FormField>
        <FormField label={t('department')}>
          <select className={SELECT_CLASS} value={values.departmentId} disabled={saving} onChange={(e) => set('departmentId', e.target.value)}>
            <option value="">{t('noDepartment')}</option>
            {(asset?.department && !departments.some((d) => d.id === asset.department!.id) ? [asset.department as Department, ...departments] : departments).map((d) => (
              <option key={d.id} value={d.id}>{lang === 'ar' ? d.nameAr : d.name}</option>
            ))}
          </select>
        </FormField>
        {text('nameAr', t('nameAr'), true)}
        {text('name', t('name'), true)}
        {text('assetCode', t('code'))}
        {text('category', t('category'))}
        {text('manufacturer', t('manufacturer'))}
        {text('model', t('model'))}
        {text('serialNumber', t('serialNumber'))}
        {text('location', t('location'))}
        {text('installDate', t('installDate'), false, { type: 'date', max: new Date().toISOString().slice(0, 10) })}
        {text('warrantyExpiry', t('warrantyExpiry'), false, { type: 'date' })}
        <FormField label={t('criticality')}>
          <select className={SELECT_CLASS} value={values.criticality} disabled={saving} onChange={(e) => set('criticality', e.target.value as AssetFormValues['criticality'])}>
            <option value="">{t('noCriticality')}</option>
            {CRITICALITIES.map((c) => <option key={c} value={c}>{enumLabel(te, 'crit', c)}</option>)}
          </select>
        </FormField>
        <FormField label={t('status')}>
          <select className={SELECT_CLASS} value={values.status} disabled={saving} onChange={(e) => set('status', e.target.value as AssetFormValues['status'])}>
            {ASSET_STATUSES.map((s) => <option key={s} value={s}>{enumLabel(te, 'assetStatus', s)}</option>)}
          </select>
        </FormField>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={saving}>{saving ? t('saving') : t('save')}</Button>
        <Button type="button" variant="outline" disabled={saving} onClick={onCancel}>{t('cancel')}</Button>
      </div>
    </form>
  );
}
