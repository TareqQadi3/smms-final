'use client';

import { PauseCircle } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState, ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { formatDuration, workflowDict } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import { describeError } from '@/modules/workflow/errors';
import type { UserRef, WorkOrderDetail } from '@/modules/workflow/types';
import { useLoad } from './useLoad';

const nameOf = (u: UserRef | undefined, isAr: boolean) => (u ? (isAr && u.fullNameAr) || u.fullName : '');

/**
 * Thread of information requests (question from staff, answer from the requester) with the SLA
 * pause indicator. The request / provide actions live in the actions panel (role gated there).
 */
export function InfoRequestThread({ wo, refreshKey = 0 }: { wo: WorkOrderDetail; refreshKey?: number }) {
  const { t, lang, isAr } = useModuleT(workflowDict);
  const list = useLoad(async () => (await workflowApi.infoRequests(wo.id)).data, `${wo.id}:${refreshKey}`);
  const paused = !!wo.slaPaused;
  const total = wo.slaPausedSeconds ?? 0;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          {t('infoThread')}
          {paused && (
            <span role="status" className="inline-flex items-center gap-1 rounded-full border border-amber-300 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
              <PauseCircle size={13} aria-hidden /> {t('slaPausedBadge')}
            </span>
          )}
        </CardTitle>
        {total > 0 && <p className="text-xs text-muted-foreground">{t('slaPausedTotal', { d: formatDuration(total, isAr) })}</p>}
      </CardHeader>
      <CardContent>
        {list.loading ? (
          <LoadingState rows={2} />
        ) : list.error ? (
          <ErrorState message={describeError(list.error, t)} onRetry={list.reload} />
        ) : !list.data || list.data.length === 0 ? (
          <EmptyState title={t('noInfoRequests')} />
        ) : (
          <ol className="space-y-3">
            {list.data.map((r) => (
              <li key={r.id} className="space-y-2 rounded-md border p-3 text-sm">
                <div>
                  <p className="text-xs text-muted-foreground">
                    {t('infoAsked')} - {nameOf(r.requestedBy, isAr)} - <span className="tabular-nums">{formatDate(r.requestedAt, lang, true)}</span>
                  </p>
                  <p className="whitespace-pre-wrap">{r.question}</p>
                </div>
                {r.answer ? (
                  <div className="rounded bg-muted/50 p-2">
                    <p className="text-xs text-muted-foreground">
                      {t('infoAnswer')} - {nameOf(r.answeredBy, isAr)} - <span className="tabular-nums">{formatDate(r.answeredAt, lang, true)}</span>
                    </p>
                    <p className="whitespace-pre-wrap">{r.answer}</p>
                  </div>
                ) : r.cancelledAt ? (
                  <p className="text-xs text-muted-foreground">{t('infoCancelled')} ({formatDate(r.cancelledAt, lang, true)})</p>
                ) : (
                  <p className="text-xs font-medium text-amber-700">{t('infoWaiting')}</p>
                )}
                {!!r.pausedSeconds && r.pausedSeconds > 0 && (
                  <p className="text-xs text-muted-foreground">{t('infoPausedFor', { d: formatDuration(r.pausedSeconds, isAr) })}</p>
                )}
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}
