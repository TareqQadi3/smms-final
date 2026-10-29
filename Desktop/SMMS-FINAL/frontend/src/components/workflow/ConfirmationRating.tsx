'use client';

import { Star } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import { describeError, parseApiError } from '@/modules/workflow/errors';
import { canReadRating } from '@/modules/workflow/permissions';
import type { WorkOrderDetail } from '@/modules/workflow/types';
import { useLoad } from './useLoad';

export function Stars({ score }: { score: number }) {
  return (
    <span className="inline-flex" aria-hidden>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star key={n} size={16} className={n <= score ? 'fill-amber-400 text-amber-400' : 'text-muted-foreground/40'} />
      ))}
    </span>
  );
}

/**
 * Read-only display of the requester's confirmation (accepted / disputed) and rating. Closing does
 * not require confirmation, so the pending state is shown for completed tickets. The requester
 * confirms and rates from "my requests", not from this staff screen.
 */
export function ConfirmationRating({ wo, role, refreshKey = 0 }: { wo: WorkOrderDetail; role?: string; refreshKey?: number }) {
  const { t, lang } = useModuleT(workflowDict);
  const eligible = ['completed', 'closed'].includes(wo.status);
  const mayRead = canReadRating(role);
  const rating = useLoad(async () => {
    if (!eligible || !mayRead) return null;
    try {
      return (await workflowApi.rating(wo.id)).data;
    } catch (e) {
      if (parseApiError(e).status === 404) return null; // not rated yet
      throw e;
    }
  }, `${wo.id}:${refreshKey}:${wo.status}`);

  if (!eligible) return null;
  const conf = wo.requesterConfirmation;

  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{t('confirmation')}</CardTitle></CardHeader>
      <CardContent className="space-y-3 text-sm">
        {conf === 'accepted' && <p className="font-medium text-green-700">{t('confirmAccepted')}</p>}
        {conf === 'disputed' && <p className="font-medium text-red-700">{t('confirmDisputed')}</p>}
        {!conf && wo.status === 'completed' && <p className="text-muted-foreground">{t('confirmPending')}</p>}
        {wo.requesterConfirmedAt && (
          <p className="text-xs text-muted-foreground">{t('confirmedAt')}: {formatDate(wo.requesterConfirmedAt, lang, true)}</p>
        )}
        {mayRead && (
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">{t('rating')}</p>
            {rating.loading ? (
              <LoadingState rows={1} />
            ) : rating.error ? (
              <ErrorState message={describeError(rating.error, t)} onRetry={rating.reload} />
            ) : rating.data ? (
              <div>
                <div className="flex items-center gap-2">
                  <Stars score={rating.data.score} />
                  <span>{t('ratingOutOf', { n: rating.data.score })}</span>
                </div>
                {rating.data.comment && <p className="mt-1 whitespace-pre-wrap">{rating.data.comment}</p>}
              </div>
            ) : (
              <p className="text-muted-foreground">{t('noRating')}</p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
