'use client';

import { useState } from 'react';
import { ArrowUpCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState, ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict, type WorkflowKey } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import { describeError } from '@/modules/workflow/errors';
import { canEscalate, canReadEscalations } from '@/modules/workflow/permissions';
import type { WorkOrderDetail } from '@/modules/workflow/types';
import { ReasonDialog } from './ReasonDialog';
import { useLoad } from './useLoad';

/** Escalation history (read roles only) and the manual "escalate to next level" action. */
export function EscalationPanel({
  wo, role, refreshKey = 0, onChanged, onConflict,
}: {
  wo: WorkOrderDetail;
  role?: string;
  refreshKey?: number;
  onChanged: () => void;
  onConflict?: () => void;
}) {
  const { t, lang } = useModuleT(workflowDict);
  const allowedToRead = canReadEscalations(role);
  const [open, setOpen] = useState(false);
  const list = useLoad(
    async () => (allowedToRead ? (await workflowApi.escalations(wo.id)).data : []),
    `${wo.id}:${refreshKey}:${allowedToRead}`
  );

  if (!allowedToRead) return null;
  const escalate = canEscalate(role, wo);
  const level = wo.escalationLevel ?? 0;
  const hint = (wo.escalationLevel ?? 0) >= 3 ? t('escalationMax') : wo.slaPaused ? t('escalationPaused') : '';

  const triggerLabel = (trigger: string) => {
    const key = `trigger_${trigger}` as WorkflowKey;
    return key in workflowDict.ar ? t(key) : trigger;
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base">
            {t('escalation')}
            {level > 0 && (
              <span className="ms-2 rounded-full bg-orange-100 px-2 py-0.5 text-xs font-medium text-orange-800">
                {t('escalationLevel', { n: level })}
              </span>
            )}
          </CardTitle>
          {escalate && (
            <Button size="sm" variant="outline" className="gap-1.5" onClick={() => setOpen(true)}>
              <ArrowUpCircle size={14} aria-hidden /> {t('escalateNow')}
            </Button>
          )}
        </div>
        {hint && !escalate && <p className="text-xs text-muted-foreground">{hint}</p>}
      </CardHeader>
      <CardContent>
        {list.loading ? (
          <LoadingState rows={2} />
        ) : list.error ? (
          <ErrorState message={describeError(list.error, t)} onRetry={list.reload} />
        ) : !list.data || list.data.length === 0 ? (
          <EmptyState title={t('noEscalations')} />
        ) : (
          <ol className="space-y-2" aria-label={t('escalation')}>
            {list.data.map((e) => (
              <li key={e.id} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="rounded bg-orange-100 px-2 py-0.5 text-xs font-semibold text-orange-800">
                    {t('escalationLevel', { n: e.level })}
                  </span>
                  <span className="font-medium">{triggerLabel(e.trigger)}</span>
                  <span className="text-xs text-muted-foreground tabular-nums">{formatDate(e.createdAt, lang, true)}</span>
                </div>
                {e.triggeredBy && (
                  <p className="mt-1 text-xs text-muted-foreground">
                    {t('triggerBy', { name: (lang === 'ar' && e.triggeredBy.fullNameAr) || e.triggeredBy.fullName })}
                    {typeof e.notifiedCount === 'number' && ` - ${t('notifiedCount', { n: e.notifiedCount })}`}
                  </p>
                )}
                {!e.triggeredBy && typeof e.notifiedCount === 'number' && (
                  <p className="mt-1 text-xs text-muted-foreground">{t('notifiedCount', { n: e.notifiedCount })}</p>
                )}
                {e.reason && <p className="mt-1">{e.reason}</p>}
              </li>
            ))}
          </ol>
        )}
      </CardContent>

      <ReasonDialog
        open={open}
        onOpenChange={setOpen}
        title={t('escalateTitle')}
        description={t('escalateDesc')}
        confirmLabel={t('escalateNow')}
        onConflict={() => { setOpen(false); onConflict?.(); }}
        onSubmit={async (reason) => {
          await workflowApi.escalate(wo.id, reason);
          setOpen(false);
          list.reload();
          onChanged();
        }}
      />
    </Card>
  );
}
