'use client';

import Link from 'next/link';
import { AlertTriangle, CloudUpload, Clock, MapPin, Package } from 'lucide-react';
import { useModuleT } from '@/lib/module-i18n';
import { relativeTime, isOverdue } from '@/modules/tech/format';
import { techDict } from '@/modules/tech/i18n';
import type { TechWorkOrder } from '@/modules/tech/types';
import { cn } from '@/lib/utils';
import { PriorityBadge, StatusBadge } from './badges';

/** One tappable row of the queue (whole card is the link; at least 72 px tall). */
export function WorkOrderCard({ wo, pending, showSla = true }: { wo: TechWorkOrder; pending?: boolean; showSla?: boolean }) {
  const { t, lang } = useModuleT(techDict);
  const overdue = !wo.slaPaused && (wo.slaBreached || isOverdue(wo.slaDeadline));
  const assetName = wo.asset ? (lang === 'ar' && wo.asset.nameAr ? wo.asset.nameAr : wo.asset.name) : null;

  return (
    <Link
      href={`/tech/${wo.id}`}
      aria-label={t('openTask', { n: wo.ticketNumber })}
      data-testid="wo-card"
      className={cn(
        'block min-h-[72px] rounded-xl border bg-card p-3.5 shadow-sm outline-none transition focus-visible:ring-3 focus-visible:ring-ring/50 active:bg-muted/60',
        wo.isEmergency ? 'border-red-400 ring-1 ring-red-300' : 'border-border'
      )}
    >
      <div className="flex flex-wrap items-center gap-1.5">
        <bdi dir="ltr" className="font-mono text-xs font-semibold tabular-nums text-blue-700 dark:text-blue-400">{wo.ticketNumber}</bdi>
        <StatusBadge status={wo.status} />
        <PriorityBadge priority={wo.priority} />
        {wo.isEmergency && (
          <span className="inline-flex items-center gap-1 rounded-md bg-red-600 px-2 py-0.5 text-xs font-bold text-white">
            <AlertTriangle size={12} aria-hidden="true" /> {t('emergency')}
          </span>
        )}
        {pending && (
          <span className="inline-flex items-center gap-1 rounded-md bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
            <CloudUpload size={12} aria-hidden="true" /> {t('pendingSync')}
          </span>
        )}
      </div>
      <p className="mt-1.5 line-clamp-2 text-base font-medium leading-snug">{wo.title}</p>
      <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {wo.locationDetail && (
          <span className="inline-flex items-center gap-1"><MapPin size={13} aria-hidden="true" />{wo.locationDetail}</span>
        )}
        {assetName && <span className="inline-flex items-center gap-1"><Package size={13} aria-hidden="true" />{assetName}</span>}
        {showSla && (
          <span className={cn('inline-flex items-center gap-1', overdue && 'font-semibold text-red-600')}>
            <Clock size={13} aria-hidden="true" />
            {wo.slaPaused
              ? t('slaPaused')
              : wo.slaDeadline
                ? t(overdue ? 'slaOverdue' : 'slaDue', { time: relativeTime(wo.slaDeadline, lang) })
                : t('slaNone')}
          </span>
        )}
      </div>
    </Link>
  );
}
