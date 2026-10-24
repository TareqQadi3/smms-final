'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, LoadingState } from '@/components/common';
import { useResource } from '@/components/tech/hooks';
import { useTech } from '@/components/tech/TechContext';
import { notificationApi } from '@/lib/api';
import { useModuleT } from '@/lib/module-i18n';
import { cn, formatDate } from '@/lib/utils';
import { techApi } from '@/modules/tech/api';
import { techDict } from '@/modules/tech/i18n';
import type { NotificationItem } from '@/modules/tech/types';

export default function TechNotificationsPage() {
  const { t, lang } = useModuleT(techDict);
  const { live, frameTick, markAllRead, refreshUnread } = useTech();
  const { data, loading, error, reload } = useResource('alerts', () => techApi.notifications(0, 30).then((r) => r.data));
  const [readLocally, setReadLocally] = useState<Set<string>>(new Set());
  const [allRead, setAllRead] = useState(false);

  useEffect(() => { if (frameTick > 0) reload(); }, [frameTick, reload]);

  const items: NotificationItem[] = data?.content ?? [];
  const isRead = (n: NotificationItem) => n.read || allRead || readLocally.has(n.id);

  const open = (n: NotificationItem) => {
    if (isRead(n)) return;
    setReadLocally((s) => new Set(s).add(n.id));
    notificationApi.markRead(n.id).then(refreshUnread).catch(() => {});
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-xl font-bold">{t('alertsTitle')}</h2>
          <p className="text-xs text-muted-foreground" role="status">{live ? t('alertsLive') : t('alertsPolling')}</p>
        </div>
        <Button
          variant="outline"
          className="h-11 min-h-11"
          disabled={items.every(isRead)}
          onClick={() => { void markAllRead().then(() => setAllRead(true)).catch(() => {}); }}
        >
          {t('alertsMarkAll')}
        </Button>
      </div>

      {data === undefined && loading && <LoadingState rows={4} />}
      {error ? <ErrorState message={t('alertsError')} onRetry={reload} /> : null}
      {data && items.length === 0 && <EmptyState title={t('alertsEmpty')} />}

      <ul className="space-y-2">
        {items.map((n) => {
          const body = (
            <>
              <p className={cn('text-sm', !isRead(n) && 'font-semibold')}>
                {!isRead(n) && <span className="me-2 inline-block size-2 rounded-full bg-red-600 align-middle" aria-hidden="true" />}
                {!isRead(n) && <span className="sr-only">{t('alertsUnread')}: </span>}
                {n.title}
              </p>
              {n.body && <p className="mt-0.5 text-xs text-muted-foreground">{n.body}</p>}
              <p className="mt-1 text-xs text-muted-foreground">{formatDate(n.createdAt, lang, true)}</p>
            </>
          );
          const cls = 'block min-h-[56px] rounded-xl border border-border bg-card p-3 outline-none focus-visible:ring-3 focus-visible:ring-ring/50';
          return (
            <li key={n.id}>
              {n.workOrderId ? (
                <Link href={`/tech/${n.workOrderId}`} className={cls} onClick={() => open(n)}>{body}</Link>
              ) : (
                <button type="button" className={cn(cls, 'w-full text-start')} onClick={() => open(n)}>{body}</button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
