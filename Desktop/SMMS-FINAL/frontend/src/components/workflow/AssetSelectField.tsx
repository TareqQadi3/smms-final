'use client';

import { useEffect, useId, useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import type { AssetOption } from '@/modules/workflow/types';
import type { AssetPickerSlotProps } from './slots';

const SEL = 'w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring';

/**
 * Built-in optional asset field for the new-request form (search + select over GET /assets of the
 * control room's center). The dedicated AssetPicker replaces it once wired through slots.ts.
 * A failed load never blocks the request: the asset is optional.
 */
export function AssetSelectField({ value, onChange, centerId, disabled, label }: AssetPickerSlotProps) {
  const { t, isAr } = useModuleT(workflowDict);
  const id = useId();
  const [q, setQ] = useState('');
  const [options, setOptions] = useState<AssetOption[]>([]);
  const [state, setState] = useState<'idle' | 'loading' | 'error'>('idle');

  useEffect(() => {
    if (!centerId) { setOptions([]); setState('idle'); return; }
    let alive = true;
    setState('loading');
    const timer = setTimeout(() => {
      workflowApi.searchAssets({ centerId, q: q.trim() || undefined })
        .then((r) => { if (alive) { setOptions(r.data.content ?? []); setState('idle'); } })
        .catch(() => { if (alive) { setOptions([]); setState('error'); } });
    }, q ? 300 : 0);
    return () => { alive = false; clearTimeout(timer); };
  }, [centerId, q]);

  const name = (a: AssetOption) => `${a.assetCode ? `${a.assetCode} - ` : ''}${(isAr && a.nameAr) || a.name}`;

  return (
    <div className="space-y-1.5">
      <Label htmlFor={`${id}-sel`}>{label ?? t('assetOptional')}</Label>
      {!centerId ? (
        <p className="py-1 text-xs text-muted-foreground">{t('assetsNeedRoom')}</p>
      ) : (
        <>
          <Input aria-label={t('assetSearch')} placeholder={t('assetSearch')} value={q} disabled={disabled}
            onChange={(e) => setQ(e.target.value)} />
          <select id={`${id}-sel`} className={SEL} value={value} disabled={disabled || state === 'loading'}
            onChange={(e) => onChange(e.target.value, options.find((o) => o.id === e.target.value))}>
            <option value="">{t('assetNone')}</option>
            {options.map((o) => <option key={o.id} value={o.id}>{name(o)}</option>)}
          </select>
          {state === 'error' && <p role="status" className="text-xs text-amber-700">{t('assetsLoadFailed')}</p>}
          {state === 'idle' && options.length === 0 && <p role="status" className="text-xs text-muted-foreground">{t('assetsNoResults')}</p>}
        </>
      )}
    </div>
  );
}
