'use client';

import { CloudUpload, RotateCw, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useModuleT, type TFn } from '@/lib/module-i18n';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import type { QueueItem } from '@/lib/offline/queue';
import { describeError } from '@/modules/tech/errors';
import { techDict, type TechKey } from '@/modules/tech/i18n';
import { cn } from '@/lib/utils';

export function describeAction(item: QueueItem, t: TFn<TechKey>): string {
  const m = item.meta ?? {};
  switch (item.kind) {
    case 'transition': {
      const key = `qAct_${m.target}` as TechKey;
      return t(key);
    }
    case 'note': return t('qAct_note', { excerpt: m.excerpt ?? '' });
    case 'consume': return t('qAct_consume', { n: m.requestNumber ?? '' });
    case 'photo': return t(m.phase === 'after' ? 'qAct_photo_after' : 'qAct_photo_before', { name: m.fileName ?? '' });
  }
}

/**
 * Actions saved on this device with their state: waiting (with attempt count), sending, or failed
 * with the reason and Retry / Discard. Pass `workOrderId` to show only that ticket's actions.
 */
export function PendingActions({ workOrderId, className }: { workOrderId?: string; className?: string }) {
  const { t } = useModuleT(techDict);
  const { items, retry, discard } = useOfflineQueue();
  const mine = workOrderId ? items.filter((i) => i.workOrderId === workOrderId) : items;
  if (mine.length === 0) return null;

  return (
    <section aria-labelledby="pending-title" className={cn('rounded-xl border border-blue-200 bg-blue-50 p-3 text-blue-950 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-50', className)}>
      <h2 id="pending-title" className="mb-2 flex items-center gap-2 text-sm font-semibold">
        <CloudUpload size={16} aria-hidden="true" />
        {t('pendingTitle')}
      </h2>
      <ul className="space-y-2" aria-live="polite">
        {mine.map((item) => {
          const err = item.lastError ? describeError(item.lastError, t) : null;
          return (
            <li key={item.id} data-testid="pending-item" data-status={item.status} className="rounded-lg bg-background/80 p-2.5 text-sm text-foreground">
              <p className="font-medium">
                {item.meta?.ticketNumber && !workOrderId && <bdi dir="ltr" className="me-1 text-xs text-muted-foreground">{item.meta.ticketNumber}</bdi>}
                {describeAction(item, t)}
              </p>
              <p className={cn('mt-0.5 text-xs', item.status === 'failed' ? 'text-destructive' : 'text-muted-foreground')}>
                {item.status === 'sending' && t('pendingSending')}
                {item.status === 'pending' && t('pendingWaiting', { n: item.attempts + 1 })}
                {item.status === 'failed' && t('pendingFailed')}
                {err && item.status === 'failed' && ` - ${err.text}`}
              </p>
              {err?.detail && item.status === 'failed' && (
                <p className="mt-0.5 text-xs text-muted-foreground"><bdi dir="auto">{t('errServerSays', { message: err.detail })}</bdi></p>
              )}
              {item.status === 'failed' && (
                <div className="mt-2 flex gap-2">
                  <Button variant="outline" className="h-11 min-h-11 flex-1" onClick={() => retry(item.id)}>
                    <RotateCw size={16} aria-hidden="true" /> {t('pendingRetry')}
                  </Button>
                  <Button variant="destructive" className="h-11 min-h-11 flex-1" onClick={() => discard(item.id)}>
                    <Trash2 size={16} aria-hidden="true" /> {t('pendingDiscard')}
                  </Button>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
