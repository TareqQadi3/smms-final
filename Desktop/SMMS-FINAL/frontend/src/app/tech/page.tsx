'use client';

import { useEffect, useMemo } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, LoadingState } from '@/components/common';
import { PendingActions } from '@/components/tech/PendingActions';
import { WorkOrderCard } from '@/components/tech/WorkOrderCard';
import { useQueueEvents, useResource } from '@/components/tech/hooks';
import { useTech } from '@/components/tech/TechContext';
import { useModuleT } from '@/lib/module-i18n';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import { formatDate } from '@/lib/utils';
import { techApi } from '@/modules/tech/api';
import { describeError } from '@/modules/tech/errors';
import { techDict } from '@/modules/tech/i18n';
import { ACTIVE_STATUSES, compareQueue } from '@/modules/tech/rules';
import type { TechWorkOrder } from '@/modules/tech/types';

/** One request per active status: exact, and a long history can never push active tickets off the first page. */
async function fetchActive(): Promise<TechWorkOrder[]> {
  const pages = await Promise.all(
    ACTIVE_STATUSES.map((status) => techApi.list({ status, page: 0, size: 50, sort: 'slaDeadline,asc' }))
  );
  return pages.flatMap((p) => p.data.content ?? []);
}

export default function TechQueuePage() {
  const { t, lang } = useModuleT(techDict);
  const { items } = useOfflineQueue();
  const { frameTick } = useTech();
  const { data, loading, error, staleSince, reload } = useResource<TechWorkOrder[]>('queue', fetchActive);

  // A pushed notification (new assignment) or a replayed action changes the queue
  useEffect(() => { if (frameTick > 0) reload(); }, [frameTick, reload]);
  useQueueEvents((e) => { if (e.type === 'done' || e.type === 'dropped') reload(); });

  const sorted = useMemo(() => (data ?? []).slice().sort(compareQueue), [data]);
  const action = sorted.filter((w) => w.status === 'assigned' || w.status === 'in_progress');
  const waiting = sorted.filter((w) => w.status === 'waiting_materials' || w.status === 'waiting_approval');
  const pendingIds = new Set(items.map((i) => i.workOrderId).filter(Boolean));

  const failure = error ? describeError(error, t) : null;

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-bold">{t('queueTitle')}</h2>
          <p className="text-sm text-muted-foreground" aria-live="polite">{t('queueActive', { n: sorted.length })}</p>
        </div>
        <Button variant="outline" className="h-11 min-h-11 px-3" onClick={reload} disabled={loading} aria-label={t('refresh')}>
          <RefreshCw size={18} className={loading ? 'animate-spin' : undefined} aria-hidden="true" />
          <span>{t('refresh')}</span>
        </Button>
      </div>

      <PendingActions />

      {staleSince && (
        <p role="status" className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          {t('cachedNotice', { time: formatDate(staleSince, lang, true) })}
        </p>
      )}

      {data === undefined && loading && <LoadingState rows={4} />}
      {failure && <ErrorState message={failure.text || t('queueLoadError')} onRetry={reload} />}

      {data !== undefined && sorted.length === 0 && (
        <EmptyState title={t('queueEmptyTitle')} description={t('queueEmptyBody')} />
      )}

      {action.length > 0 && (
        <section aria-labelledby="sec-action" className="space-y-2">
          <h3 id="sec-action" className="text-sm font-semibold text-muted-foreground">{t('sectionNeedsAction')} ({action.length})</h3>
          <ul className="space-y-2">
            {action.map((wo) => (
              <li key={wo.id}><WorkOrderCard wo={wo} pending={pendingIds.has(wo.id)} /></li>
            ))}
          </ul>
        </section>
      )}

      {waiting.length > 0 && (
        <section aria-labelledby="sec-waiting" className="space-y-2">
          <h3 id="sec-waiting" className="text-sm font-semibold text-muted-foreground">{t('sectionWaiting')} ({waiting.length})</h3>
          <ul className="space-y-2">
            {waiting.map((wo) => (
              <li key={wo.id}><WorkOrderCard wo={wo} pending={pendingIds.has(wo.id)} /></li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
