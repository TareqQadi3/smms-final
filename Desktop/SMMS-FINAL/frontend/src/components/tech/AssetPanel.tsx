'use client';

import { useEffect, useState } from 'react';
import { LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { techApi } from '@/modules/tech/api';
import { techDict } from '@/modules/tech/i18n';
import type { AssetWorkOrderRow, TechWorkOrder } from '@/modules/tech/types';
import { StatusBadge } from './badges';
import { Section } from './Section';

/** The asset the ticket is about and its earlier work orders (GET /assets/{id}/work-orders). */
export function AssetPanel({ wo }: { wo: TechWorkOrder }) {
  const { t, lang } = useModuleT(techDict);
  const asset = wo.asset;
  const [rows, setRows] = useState<AssetWorkOrderRow[] | null>(null);
  const [state, setState] = useState<'loading' | 'ok' | 'error'>('loading');

  useEffect(() => {
    if (!asset) return;
    let cancelled = false;
    setState('loading');
    techApi.assetWorkOrders(asset.id, 6)
      .then((r) => { if (!cancelled) { setRows(r.data.content.filter((x) => x.id !== wo.id).slice(0, 5)); setState('ok'); } })
      .catch(() => { if (!cancelled) setState('error'); });
    return () => { cancelled = true; };
  }, [asset, wo.id]);

  const name = asset ? (lang === 'ar' && asset.nameAr ? asset.nameAr : asset.name) : '';

  return (
    <Section title={t('secAsset')}>
      {!asset ? (
        <p className="text-sm text-muted-foreground">{t('assetNone')}</p>
      ) : (
        <div className="space-y-2">
          <p className="text-sm font-medium">
            {name}
            {asset.assetCode && <span className="ms-2 text-xs font-normal text-muted-foreground">{t('assetCode', { code: asset.assetCode })}</span>}
          </p>
          <h4 className="text-sm font-medium text-muted-foreground">{t('assetPrev')}</h4>
          {state === 'loading' && <LoadingState rows={2} />}
          {state === 'error' && <p role="alert" className="text-sm text-destructive">{t('assetHistoryError')}</p>}
          {state === 'ok' && rows && rows.length === 0 && <p className="text-sm text-muted-foreground">{t('assetHistoryNone')}</p>}
          {state === 'ok' && rows && rows.length > 0 && (
            <ul className="space-y-2">
              {rows.map((r) => (
                <li key={r.id} className="rounded-lg bg-muted/50 p-2.5 text-sm" data-testid="asset-history-row">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <bdi dir="ltr" className="font-mono text-xs font-semibold">{r.ticketNumber}</bdi>
                    <StatusBadge status={r.status} />
                    {r.preventive && <span className="rounded-md bg-blue-100 px-2 py-0.5 text-xs text-blue-800">{t('preventive')}</span>}
                  </div>
                  <p className="mt-1 font-medium">{r.title}</p>
                  {r.techReport && <p className="mt-0.5 line-clamp-3 text-xs text-muted-foreground"><bdi dir="auto">{r.techReport}</bdi></p>}
                  <p className="mt-0.5 text-xs text-muted-foreground">{formatDate(r.completedDate ?? r.createdAt, lang)}</p>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Section>
  );
}
