'use client';

import Link from 'next/link';
import { useCallback, useState, type FormEvent } from 'react';
import { FormField, LoadingState } from '@/components/common';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { assetsApi } from '@/modules/assets/api';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { assetsDict } from '@/modules/assets/i18n';
import { apiError, useLoad } from '@/modules/assets/lib';
import { ASSET_MATERIALS, ASSET_READ, ASSET_WRITE, DOWNTIME_WRITE, hasRole } from '@/modules/assets/roles';
import { PermissionDenied } from '@/components/common';
import { ApiErrorView } from './ApiErrorView';
import { AssetStateBadges } from './AssetBadges';
import { DowntimePanel, HistoryPanel, MaterialsPanel } from './AssetPanels';
import { TabBar } from './TabBar';

export function AssetDetailView({ id }: { id: string }) {
  const { t, lang } = useModuleT(assetsDict);
  const { t: te } = useModuleT(enumDict);
  const role = useAuthStore((s) => s.user?.role);
  const allowed = hasRole(role, ASSET_READ);
  const fetcher = useCallback(() => assetsApi.get(id), [id]);
  const { data: asset, error, loading, reload } = useLoad(fetcher, allowed);
  const [tab, setTab] = useState('details');
  const [dOpen, setDOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [busy, setBusy] = useState(false);
  const [dError, setDError] = useState('');
  const [done, setDone] = useState('');

  if (!allowed) return <PermissionDenied />;
  if (loading && !asset) return <LoadingState />;
  if (error && !asset) return <ApiErrorView error={error} onRetry={reload} />;
  if (!asset) return null;

  const writer = hasRole(role, ASSET_WRITE);
  const retired = !!asset.decommissionedAt;
  const name = lang === 'ar' ? asset.nameAr || asset.name : asset.name || asset.nameAr;

  const tabs = [
    { id: 'details', label: t('details') },
    { id: 'history', label: t('history') },
    ...(hasRole(role, ASSET_MATERIALS) ? [{ id: 'materials', label: t('materials') }] : []),
    { id: 'downtime', label: t('downtime') },
  ];

  const decommission = async (e: FormEvent) => {
    e.preventDefault();
    if (!reason.trim()) {
      setReasonError(t('vReasonRequired'));
      return;
    }
    setReasonError('');
    setBusy(true);
    setDError('');
    try {
      await assetsApi.decommission(asset.id, reason.trim());
      setDOpen(false);
      setReason('');
      setDone(t('decommissionedOk'));
      reload();
    } catch (err) {
      const { status, message } = apiError(err);
      setDError(status === 409 ? t('conflictReload') : message || t('saveFailed'));
    } finally {
      setBusy(false);
    }
  };

  const fields: [string, string | null | undefined][] = [
    [t('code'), asset.assetCode],
    [t('center'), lang === 'ar' ? asset.center?.nameAr : asset.center?.name],
    [t('department'), asset.department ? (lang === 'ar' ? asset.department.nameAr : asset.department.name) : null],
    [t('category'), asset.category],
    [t('manufacturer'), asset.manufacturer],
    [t('model'), asset.model],
    [t('serialNumber'), asset.serialNumber],
    [t('location'), asset.location],
    [t('installDate'), asset.installDate ? formatDate(asset.installDate, lang) : null],
    [t('warrantyExpiry'), asset.warrantyExpiry ? formatDate(asset.warrantyExpiry, lang) : null],
    [t('criticality'), asset.criticality ? enumLabel(te, 'crit', asset.criticality) : null],
    [t('openOrders'), String(asset.openWorkOrders)],
  ];

  return (
    <div className="space-y-5">
      <Link href="/assets" className="text-sm text-primary underline-offset-4 hover:underline">{t('back')}</Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-bold">{name}</h1>
          <AssetStateBadges asset={asset} />
        </div>
        {writer && !retired && (
          <div className="flex flex-wrap gap-2">
            <Link
              href={`/assets/${asset.id}/edit`}
              className="inline-flex h-8 max-md:min-h-10 items-center rounded-lg border px-3 text-sm font-medium hover:bg-muted"
            >
              {t('edit')}
            </Link>
            <Button type="button" variant="destructive" onClick={() => { setDError(''); setDOpen(true); }}>
              {t('decommission')}
            </Button>
          </div>
        )}
      </div>

      <div aria-live="polite">{done && <p role="status" className="text-sm text-green-700 dark:text-green-500">{done}</p>}</div>
      {retired && (
        <p className="rounded-lg border p-3 text-sm" role="note">
          {t('decommissionedAt')}: {formatDate(asset.decommissionedAt, lang)}
          {asset.decommissionReason ? ` - ${asset.decommissionReason}` : ''}
        </p>
      )}

      <TabBar tabs={tabs} active={tab} onChange={setTab} idPrefix="asset" label={name} />
      <div role="tabpanel" id={`asset-panel-${tab}`} aria-labelledby={`asset-tab-${tab}`} tabIndex={0}>
        {tab === 'details' && (
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
            {fields.map(([k, v]) => (
              <div key={k}>
                <dt className="text-xs text-muted-foreground">{k}</dt>
                <dd className="text-sm">{v || '-'}</dd>
              </div>
            ))}
          </dl>
        )}
        {tab === 'history' && <HistoryPanel assetId={asset.id} />}
        {tab === 'materials' && <MaterialsPanel assetId={asset.id} />}
        {tab === 'downtime' && (
          <DowntimePanel assetId={asset.id} canWrite={hasRole(role, DOWNTIME_WRITE) && !retired} onChanged={reload} />
        )}
      </div>

      <Dialog open={dOpen} onOpenChange={(o) => !busy && setDOpen(o)}>
        <DialogContent dir={lang === 'ar' ? 'rtl' : 'ltr'}>
          <form onSubmit={decommission} noValidate className="space-y-4">
            <DialogHeader>
              <DialogTitle>{t('decommissionTitle')}</DialogTitle>
              <DialogDescription>{t('decommissionBody')}</DialogDescription>
            </DialogHeader>
            <FormField label={t('reason')} required error={reasonError}>
              <Textarea rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} disabled={busy} />
            </FormField>
            {dError && <p role="alert" className="text-sm text-destructive">{dError}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={busy} onClick={() => setDOpen(false)}>{t('cancel')}</Button>
              <Button type="submit" variant="destructive" disabled={busy}>{busy ? t('saving') : t('decommission')}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
