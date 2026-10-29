'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { AssetPicker } from '@/components/assets/AssetPicker';
import { SELECT_CLASS } from '@/components/assets/styles';
import { FormField } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { useModuleT } from '@/lib/module-i18n';
import { FREQUENCY_UNITS, PRIORITIES, SUB_TYPES, enumDict, enumLabel } from '@/modules/assets/enums';
import { apiError } from '@/modules/assets/lib';
import type { AssetOption } from '@/modules/assets/types';
import { pmApi } from '../api';
import { pmDict } from '../i18n';
import type { ControlRoomLite, Plan, PlanFormValues } from '../types';
import { validatePlan, type PlanErrors } from '../validation';

const EMPTY: PlanFormValues = {
  assetId: '', centerId: '', controlRoomId: '', subType: 'preventive_maintenance', priority: 'medium', title: '',
  instructions: '', frequencyUnit: 'month', frequencyInterval: '1', leadDays: '0', startDate: '', nextDueDate: '',
};

function fromPlan(p: Plan): PlanFormValues {
  return {
    assetId: p.assetId, centerId: p.centerId, controlRoomId: p.controlRoomId, subType: p.subType, priority: p.priority ?? '',
    title: p.title, instructions: p.instructions ?? '', frequencyUnit: p.frequencyUnit,
    frequencyInterval: String(p.frequencyInterval), leadDays: String(p.leadDays), startDate: p.startDate, nextDueDate: '',
  };
}

export function PlanForm({
  mode,
  plan,
  onSaved,
  onCancel,
}: {
  mode: 'create' | 'edit';
  plan?: Plan;
  onSaved: (p: Plan) => void;
  onCancel: () => void;
}) {
  const { t, lang } = useModuleT(pmDict);
  const { t: te } = useModuleT(enumDict);
  const [values, setValues] = useState<PlanFormValues>(plan ? fromPlan(plan) : EMPTY);
  const [version, setVersion] = useState(plan?.version ?? 0);
  const [asset, setAsset] = useState<AssetOption | null>(null);
  const [errors, setErrors] = useState<PlanErrors>({});
  const [rooms, setRooms] = useState<ControlRoomLite[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [info, setInfo] = useState('');
  const formRef = useRef<HTMLFormElement>(null);
  const set = <K extends keyof PlanFormValues>(k: K, v: PlanFormValues[K]) => setValues((s) => ({ ...s, [k]: v }));

  useEffect(() => {
    if (!values.centerId) {
      setRooms([]);
      return;
    }
    let cancelled = false;
    pmApi.controlRooms(values.centerId).then((r) => !cancelled && setRooms(r)).catch(() => !cancelled && setRooms([]));
    return () => {
      cancelled = true;
    };
  }, [values.centerId]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    setInfo('');
    const errs = validatePlan(values, mode, t);
    setErrors(errs);
    if (Object.keys(errs).length > 0) {
      requestAnimationFrame(() => formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
      return;
    }
    setSaving(true);
    try {
      const saved = mode === 'create' ? await pmApi.create(values) : await pmApi.update(plan!.id, values, version);
      onSaved(saved);
    } catch (err) {
      const { status, message } = apiError(err);
      if (status === 409 && mode === 'edit') setConflict(true);
      else setFormError(message || t('saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const reload = async () => {
    if (!plan) return;
    try {
      const fresh = await pmApi.get(plan.id);
      setValues(fromPlan(fresh));
      setVersion(fresh.version);
      setConflict(false);
      setErrors({});
      setInfo(t('reloaded'));
    } catch (err) {
      setFormError(apiError(err).message || t('loadFailed'));
    }
  };

  return (
    <form ref={formRef} onSubmit={submit} noValidate className="space-y-5" aria-label={mode === 'create' ? t('createTitle') : t('editTitle')}>
      {conflict && (
        <div role="alert" className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          <p>{t('conflictReload')}</p>
          <Button type="button" variant="outline" size="sm" onClick={reload}>{t('reload')}</Button>
        </div>
      )}
      {formError && <p role="alert" className="rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">{formError}</p>}
      {info && <p role="status" className="text-sm text-green-700 dark:text-green-500">{info}</p>}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          {mode === 'create' ? (
            <AssetPicker
              required
              label={t('asset')}
              value={asset}
              error={errors.assetId}
              onChange={(a) => {
                setAsset(a);
                setValues((s) => ({ ...s, assetId: a?.id ?? '', centerId: a?.center?.id ?? '', controlRoomId: '' }));
              }}
            />
          ) : (
            <p className="text-sm">
              <span className="text-muted-foreground">{t('asset')}: </span>
              {plan?.assetCode ? `${plan.assetCode} - ` : ''}{plan?.assetName}
              <span className="block text-xs text-muted-foreground">{t('assetLocked')}</span>
            </p>
          )}
        </div>
        <FormField label={t('planName')} required error={errors.title} className="sm:col-span-2">
          <Input value={values.title} maxLength={400} onChange={(e) => set('title', e.target.value)} disabled={saving} />
        </FormField>
        <FormField label={t('controlRoom')} required error={errors.controlRoomId} hint={!values.centerId ? t('selectAssetFirst') : undefined}>
          <select className={SELECT_CLASS} value={values.controlRoomId} disabled={saving || !values.centerId} onChange={(e) => set('controlRoomId', e.target.value)}>
            <option value="">{t('selectControlRoom')}</option>
            {rooms.map((r) => <option key={r.id} value={r.id}>{lang === 'ar' ? r.nameAr : r.name}</option>)}
          </select>
        </FormField>
        <FormField label={t('subType')} required>
          <select className={SELECT_CLASS} value={values.subType} disabled={saving} onChange={(e) => set('subType', e.target.value)}>
            {SUB_TYPES.map((s) => <option key={s} value={s}>{enumLabel(te, 'subType', s)}</option>)}
          </select>
        </FormField>
        <FormField label={t('priority')}>
          <select className={SELECT_CLASS} value={values.priority} disabled={saving} onChange={(e) => set('priority', e.target.value)}>
            <option value="">{t('noPriority')}</option>
            {PRIORITIES.map((s) => <option key={s} value={s}>{enumLabel(te, 'priority', s)}</option>)}
          </select>
        </FormField>
        <FormField label={t('frequencyUnit')} required>
          <select className={SELECT_CLASS} value={values.frequencyUnit} disabled={saving} onChange={(e) => set('frequencyUnit', e.target.value as PlanFormValues['frequencyUnit'])}>
            {FREQUENCY_UNITS.map((s) => <option key={s} value={s}>{enumLabel(te, 'freq', s)}</option>)}
          </select>
        </FormField>
        <FormField label={t('frequencyInterval')} required error={errors.frequencyInterval}>
          <Input type="number" inputMode="numeric" min={1} max={366} value={values.frequencyInterval} onChange={(e) => set('frequencyInterval', e.target.value)} disabled={saving} />
        </FormField>
        <FormField label={t('leadDays')} error={errors.leadDays}>
          <Input type="number" inputMode="numeric" min={0} max={60} value={values.leadDays} onChange={(e) => set('leadDays', e.target.value)} disabled={saving} />
        </FormField>
        {mode === 'create' ? (
          <FormField label={t('startDate')} required error={errors.startDate}>
            <Input type="date" value={values.startDate} onChange={(e) => set('startDate', e.target.value)} disabled={saving} />
          </FormField>
        ) : (
          <FormField label={t('nextDueDate')} error={errors.nextDueDate} hint={t('nextDueHint')}>
            <Input type="date" value={values.nextDueDate} onChange={(e) => set('nextDueDate', e.target.value)} disabled={saving} />
          </FormField>
        )}
        <FormField label={t('instructions')} error={errors.instructions} className="sm:col-span-2">
          <Textarea rows={4} value={values.instructions} onChange={(e) => set('instructions', e.target.value)} disabled={saving} />
        </FormField>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={saving}>{saving ? t('saving') : t('save')}</Button>
        <Button type="button" variant="outline" disabled={saving} onClick={onCancel}>{t('cancel')}</Button>
      </div>
    </form>
  );
}
