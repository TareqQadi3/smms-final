'use client';

import { useMemo, useState, type FormEvent } from 'react';
import { SELECT_CLASS } from '@/components/assets/styles';
import { TabBar } from '@/components/assets/TabBar';
import { FormField, PermissionDenied } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { useCenters } from '@/modules/assets/hooks';
import { addDaysIso, todayIso, useLoad } from '@/modules/assets/lib';
import { hasRole, REPORT_INVENTORY, REPORT_READ } from '@/modules/assets/roles';
import { reportsApi } from '../api';
import { reportsDict } from '../i18n';
import type { ReportFilters } from '../types';
import { MAX_RANGE_DAYS, validateRange } from '../validation';
import { AssetsPmPanel, EscalationsPanel, InventoryPanel, SlaPanel, TechniciansPanel, TurnaroundPanel, VolumePanel } from './Panels';

type TabId = 'volume' | 'turnaround' | 'sla' | 'escalations' | 'technicians' | 'assets' | 'inventory';

function defaultFilters(): ReportFilters {
  const to = todayIso();
  return { from: addDaysIso(to, -29), to, centerId: '', regionId: '' };
}

export function ReportsView() {
  const { t, lang } = useModuleT(reportsDict);
  const role = useAuthStore((s) => s.user?.role);
  const fullAccess = hasRole(role, REPORT_READ);
  const inventoryOnly = !fullAccess && hasRole(role, REPORT_INVENTORY);
  const centers = useCenters();
  const regionScoped = role === 'system_admin' || role === 'region_supervisor';
  const regions = useLoad(reportsApi.regions, regionScoped);
  const [draft, setDraft] = useState<ReportFilters>(defaultFilters);
  const [applied, setApplied] = useState<ReportFilters>(draft);
  const [rangeError, setRangeError] = useState('');
  const [tab, setTab] = useState<TabId>(inventoryOnly ? 'inventory' : 'volume');

  const tabs = useMemo(
    () =>
      (inventoryOnly
        ? [{ id: 'inventory', label: t('tabInventory') }]
        : [
            { id: 'volume', label: t('tabVolume') },
            { id: 'turnaround', label: t('tabTurnaround') },
            { id: 'sla', label: t('tabSla') },
            { id: 'escalations', label: t('tabEscalations') },
            { id: 'technicians', label: t('tabTechnicians') },
            { id: 'assets', label: t('tabAssets') },
            { id: 'inventory', label: t('tabInventory') },
          ]) as { id: TabId; label: string }[],
    [inventoryOnly, t]
  );

  if (!fullAccess && !inventoryOnly) return <PermissionDenied />;

  const apply = (e: FormEvent) => {
    e.preventDefault();
    const err = validateRange(draft.from, draft.to);
    if (err) {
      setRangeError(t(err, { n: MAX_RANGE_DAYS } as never));
      return;
    }
    setRangeError('');
    setApplied(draft);
  };

  const centerList = centers.data ?? [];
  const showCenter = centerList.length > 1 || (regionScoped && centerList.length > 0);
  const visibleCenters = draft.regionId
    ? centerList.filter((c) => (c as { region?: { id: string } }).region?.id === draft.regionId || !(c as { region?: unknown }).region)
    : centerList;

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold">{t('title')}</h1>
        <p className="text-sm text-muted-foreground">{t('subtitle')}</p>
      </div>

      <form onSubmit={apply} noValidate aria-label={t('filters')} className="space-y-3 rounded-lg border p-3">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <FormField label={t('from')} required>
            <Input type="date" value={draft.from} aria-invalid={rangeError ? true : undefined} aria-describedby={rangeError ? 'range-error' : undefined} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          </FormField>
          <FormField label={t('to')} required>
            <Input type="date" value={draft.to} aria-invalid={rangeError ? true : undefined} aria-describedby={rangeError ? 'range-error' : undefined} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          </FormField>
          {regionScoped && regions.data && regions.data.length > 0 && (
            <FormField label={t('region')}>
              <select className={SELECT_CLASS} value={draft.regionId} onChange={(e) => setDraft({ ...draft, regionId: e.target.value, centerId: '' })}>
                <option value="">{t('allRegions')}</option>
                {regions.data.map((r) => <option key={r.id} value={r.id}>{lang === 'ar' ? r.nameAr : r.name}</option>)}
              </select>
            </FormField>
          )}
          {showCenter && (
            <FormField label={t('center')}>
              <select className={SELECT_CLASS} value={draft.centerId} onChange={(e) => setDraft({ ...draft, centerId: e.target.value })}>
                <option value="">{t('allCenters')}</option>
                {visibleCenters.map((c) => <option key={c.id} value={c.id}>{lang === 'ar' ? c.nameAr : c.name}</option>)}
              </select>
            </FormField>
          )}
          <div className="flex items-end"><Button type="submit" className="w-full">{t('apply')}</Button></div>
        </div>
        {rangeError && <p id="range-error" role="alert" className="text-sm text-destructive">{rangeError}</p>}
        <p className="text-xs text-muted-foreground">
          {t('rangeHint', { n: MAX_RANGE_DAYS })} {regionScoped ? t('regionNote') : ''}
        </p>
      </form>

      <TabBar tabs={tabs} active={tab} onChange={(id) => setTab(id as TabId)} idPrefix="rep" label={t('tabs')} />
      <div role="tabpanel" id={`rep-panel-${tab}`} aria-labelledby={`rep-tab-${tab}`} tabIndex={0} className="space-y-4">
        {tab === 'volume' && <VolumePanel filters={applied} />}
        {tab === 'turnaround' && <TurnaroundPanel filters={applied} />}
        {tab === 'sla' && <SlaPanel filters={applied} />}
        {tab === 'escalations' && <EscalationsPanel filters={applied} />}
        {tab === 'technicians' && <TechniciansPanel filters={applied} />}
        {tab === 'assets' && <AssetsPmPanel filters={applied} />}
        {tab === 'inventory' && <InventoryPanel filters={applied} includePurchasing={fullAccess} />}
      </div>
    </div>
  );
}
