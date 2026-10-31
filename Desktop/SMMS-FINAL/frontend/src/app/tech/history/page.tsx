'use client';

import { useState } from 'react';
import { EmptyState, ErrorState, LoadingState, Pagination } from '@/components/common';
import { WorkOrderCard } from '@/components/tech/WorkOrderCard';
import { useResource } from '@/components/tech/hooks';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { techApi } from '@/modules/tech/api';
import { describeError } from '@/modules/tech/errors';
import { techDict } from '@/modules/tech/i18n';

type Filter = 'completed' | 'closed' | 'cancelled';
const FILTERS: Filter[] = ['completed', 'closed', 'cancelled'];
const PAGE_SIZE = 15;

export default function TechHistoryPage() {
  const { t } = useModuleT(techDict);
  const [filter, setFilter] = useState<Filter>('completed');
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useResource(
    `history:${filter}:${page}`,
    () => techApi.list({ status: filter, page, size: PAGE_SIZE, sort: 'updatedAt,desc' }).then((r) => r.data),
    [filter, page]
  );
  const label: Record<Filter, string> = { completed: t('filterCompleted'), closed: t('filterClosed'), cancelled: t('filterCancelled') };
  const failure = error ? describeError(error, t) : null;

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">{t('historyTitle')}</h2>

      <div role="group" aria-label={t('filterLabel')} className="flex gap-2">
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={filter === f}
            onClick={() => { setFilter(f); setPage(0); }}
            className={cn(
              'min-h-11 flex-1 rounded-lg border px-3 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
              filter === f ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-card'
            )}
          >
            {label[f]}
          </button>
        ))}
      </div>

      {data === undefined && loading && <LoadingState rows={4} />}
      {failure && <ErrorState message={failure.text} onRetry={reload} />}
      {data && data.content.length === 0 && <EmptyState title={t('historyEmpty')} />}
      {data && data.content.length > 0 && (
        <ul className="space-y-2">
          {data.content.map((wo) => (
            <li key={wo.id}><WorkOrderCard wo={wo} showSla={false} /></li>
          ))}
        </ul>
      )}
      {data && <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} disabled={loading} />}
    </div>
  );
}
