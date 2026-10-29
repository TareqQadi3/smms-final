'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Plus } from 'lucide-react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { EmptyState, ErrorState, LoadingState, PermissionDenied, Pagination } from '@/components/common';
import { RequestCard } from '@/components/requests/RequestCard';
import { useResource } from '@/components/tech/hooks';
import { useAuthStore } from '@/store/authStore';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { requestsApi } from '@/modules/requests/api';
import { describeRequestError } from '@/modules/requests/errors';
import { requestsDict } from '@/modules/requests/i18n';

type Filter = 'all' | 'open' | 'done';
const PAGE_SIZE = 12;

export default function MyRequestsPage() {
  const { t } = useModuleT(requestsDict);
  const role = useAuthStore((s) => s.user?.role);
  const [filter, setFilter] = useState<Filter>('all');
  const [page, setPage] = useState(0);
  // `status` is a single-valued filter on the server: "open" is every status except the finished ones, so it is filtered client-side
  const { data, loading, error, reload } = useResource(
    null,
    () => requestsApi.list({ page, size: PAGE_SIZE, ...(filter === 'done' ? { status: 'completed' } : {}) }).then((r) => r.data),
    [page, filter]
  );
  const items = (data?.content ?? []).filter((w) => (filter === 'open' ? !['completed', 'closed', 'cancelled'].includes(w.status) : true));
  const failure = error ? describeRequestError(error, t) : null;
  const filters: { id: Filter; label: string }[] = [
    { id: 'all', label: t('filterAll') },
    { id: 'open', label: t('filterOpen') },
    { id: 'done', label: t('filterDone') },
  ];

  return (
    <DashboardLayout>
      <div className="mx-auto max-w-2xl space-y-4">
        {role && role !== 'resident' && role !== 'department' ? (
          <PermissionDenied title={t('deniedTitle')} description={t('deniedBody')} />
        ) : (
          <>
            <div className="flex items-start justify-between gap-3">
              <div>
                <h1 className="text-xl font-bold">{t('listTitle')}</h1>
                <p className="text-sm text-muted-foreground">{t('listSubtitle')}</p>
              </div>
              <Link href="/new-request" className="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                <Plus size={16} aria-hidden="true" /> {t('newRequest')}
              </Link>
            </div>

            <div role="group" aria-label={t('filterLabel')} className="flex gap-2">
              {filters.map((f) => (
                <button
                  key={f.id}
                  type="button"
                  aria-pressed={filter === f.id}
                  onClick={() => { setFilter(f.id); setPage(0); }}
                  className={cn(
                    'min-h-11 flex-1 rounded-lg border px-3 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                    filter === f.id ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-card'
                  )}
                >
                  {f.label}
                </button>
              ))}
            </div>

            {data === undefined && loading && <LoadingState rows={4} />}
            {failure && <ErrorState message={failure.text} onRetry={reload} />}
            {data && items.length === 0 && <EmptyState title={t('listEmptyTitle')} description={t('listEmptyBody')} />}
            {items.length > 0 && (
              <ul className="space-y-2">
                {items.map((w) => <li key={w.id}><RequestCard wo={w} /></li>)}
              </ul>
            )}
            {data && <Pagination page={data.number} totalPages={data.totalPages} onPageChange={setPage} disabled={loading} />}
          </>
        )}
      </div>
    </DashboardLayout>
  );
}
