'use client';

import Link from 'next/link';
import { AlertTriangle, MessageCircleQuestion, Star, CheckCircle2 } from 'lucide-react';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { requestsDict } from '@/modules/requests/i18n';
import { awaitingConfirmation, needsInfo, ratingEligible } from '@/modules/requests/rules';
import type { RequestWorkOrder } from '@/modules/requests/types';
import { PriorityBadge, StatusBadge } from '@/components/tech/badges';
import { cn } from '@/lib/utils';

export function RequestCard({ wo }: { wo: RequestWorkOrder }) {
  const { t, lang } = useModuleT(requestsDict);
  const info = needsInfo(wo);
  const confirm = awaitingConfirmation(wo);
  const rate = ratingEligible(wo);
  return (
    <Link
      href={`/my-requests/${wo.id}`}
      aria-label={t('openRequest', { n: wo.ticketNumber })}
      data-testid="request-card"
      className={cn(
        'block min-h-[72px] rounded-xl border bg-card p-3.5 shadow-sm outline-none transition focus-visible:ring-3 focus-visible:ring-ring/50 active:bg-muted/60',
        info || confirm ? 'border-amber-400' : 'border-border'
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
      </div>
      <p className="mt-1.5 line-clamp-2 text-base font-medium leading-snug">{wo.title}</p>
      <p className="mt-1 text-xs text-muted-foreground">{t('created', { date: formatDate(wo.createdAt, lang) })}</p>
      {(info || confirm || rate) && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {info && <span className="inline-flex items-center gap-1 rounded-md bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-900"><MessageCircleQuestion size={12} aria-hidden="true" />{t('needsInfo')}</span>}
          {confirm && <span className="inline-flex items-center gap-1 rounded-md bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-900"><CheckCircle2 size={12} aria-hidden="true" />{t('needsConfirm')}</span>}
          {rate && <span className="inline-flex items-center gap-1 rounded-md bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-900"><Star size={12} aria-hidden="true" />{t('canRate')}</span>}
        </div>
      )}
    </Link>
  );
}
