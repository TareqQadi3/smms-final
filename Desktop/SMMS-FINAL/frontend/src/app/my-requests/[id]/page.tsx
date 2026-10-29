'use client';

import { useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { AlertTriangle, ArrowLeft, ArrowRight, RefreshCw } from 'lucide-react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { Button } from '@/components/ui/button';
import { ErrorState, LoadingState, PermissionDenied } from '@/components/common';
import { ConfirmationPanel, InfoRequestPanel, RatingPanel, StatusLinkPanel } from '@/components/requests/Panels';
import { Section } from '@/components/tech/Section';
import { PriorityBadge, StatusBadge } from '@/components/tech/badges';
import { useResource } from '@/components/tech/hooks';
import { tStatus } from '@/lib/i18n';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate, cn } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { requestsApi } from '@/modules/requests/api';
import { describeRequestError } from '@/modules/requests/errors';
import { requestsDict } from '@/modules/requests/i18n';
import { needsInfo, ratingEligible } from '@/modules/requests/rules';

export default function MyRequestDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Array.isArray(params.id) ? params.id[0] : params.id;
  const { t, lang, isAr } = useModuleT(requestsDict);
  const role = useAuthStore((s) => s.user?.role);
  const wo = useResource(null, () => requestsApi.get(id).then((r) => r.data), [id]);
  const history = useResource(null, () => requestsApi.history(id).then((r) => r.data), [id]);
  const reload = useCallback(() => { wo.reload(); history.reload(); }, [wo, history]);

  const Back = isAr ? ArrowRight : ArrowLeft;
  const back = (
    <Link href="/my-requests" className="inline-flex min-h-11 items-center gap-1.5 text-sm font-medium text-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
      <Back size={18} aria-hidden="true" /> {t('back')}
    </Link>
  );

  let body: React.ReactNode;
  if (role && role !== 'resident' && role !== 'department') {
    body = <PermissionDenied title={t('deniedTitle')} description={t('deniedBody')} />;
  } else if (wo.data === undefined && wo.loading) {
    body = <LoadingState rows={5} />;
  } else if (wo.data === undefined) {
    const f = describeRequestError(wo.error, t);
    body = <ErrorState title={f.status === 404 ? t('notFound') : t('loadError')} message={f.status === 404 ? '' : f.text} onRetry={f.status === 404 ? undefined : reload} />;
  } else {
    const w = wo.data;
    // Status changes only: work notes and attachment rows of the technicians are not for the requester
    const steps = (history.data ?? []).filter((h) => h.fromStatus !== h.toStatus || h.stage === 'info_requested');
    const infoActive = needsInfo(w);
    body = (
      <div className="space-y-3.5">
        <header className="rounded-xl border border-border bg-card p-3.5 shadow-sm">
          <div className="flex flex-wrap items-center gap-1.5">
            <bdi dir="ltr" className="font-mono text-sm font-semibold tabular-nums text-blue-700 dark:text-blue-400">{w.ticketNumber}</bdi>
            <StatusBadge status={w.status} />
            <PriorityBadge priority={w.priority} />
            {w.isEmergency && (
              <span className="inline-flex items-center gap-1 rounded-md bg-red-600 px-2 py-0.5 text-xs font-bold text-white">
                <AlertTriangle size={12} aria-hidden="true" /> {t('emergency')}
              </span>
            )}
          </div>
          <h1 className="mt-2 text-lg font-bold leading-snug">{w.title}</h1>
        </header>

        {infoActive && <InfoRequestPanel wo={w} active onChanged={reload} />}
        <ConfirmationPanel wo={w} onChanged={reload} />
        {ratingEligible(w) && <RatingPanel wo={w} />}

        <Section title={t('secSummary')}>
          <dl className="space-y-2.5 text-sm">
            <Field label={t('fieldDescription')} value={w.description} />
            <Field label={t('fieldLocation')} value={w.locationDetail} />
            <Field label={t('fieldCenter')} value={w.center ? (isAr && w.center.nameAr ? w.center.nameAr : w.center.name) : undefined} />
            <Field label={t('fieldAsset')} value={w.asset ? (isAr && w.asset.nameAr ? w.asset.nameAr : w.asset.name) : undefined} />
            <Field label={t('fieldCreated')} value={formatDate(w.createdAt, lang, true)} />
            <Field label={t('fieldCompleted')} value={w.completedDate ? formatDate(w.completedDate, lang, true) : undefined} />
            <Field label={t('fieldCancelReason')} value={w.status === 'cancelled' ? w.rejectionReason : undefined} />
          </dl>
        </Section>

        <Section title={t('secProgress')}>
          {steps.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('progressNone')}</p>
          ) : (
            <ol className="space-y-2 border-s-2 border-border ps-3">
              {steps.slice().reverse().map((h) => (
                <li key={h.id} className="text-sm">
                  <p className="font-medium">{tStatus(h.toStatus, lang)}</p>
                  <p className="text-xs text-muted-foreground">{formatDate(h.createdAt, lang, true)}</p>
                </li>
              ))}
            </ol>
          )}
        </Section>

        {/* Past questions stay readable after they were answered */}
        {!infoActive && <InfoRequestPanel wo={w} active={false} onChanged={reload} />}
        <StatusLinkPanel wo={w} />
      </div>
    );
  }

  return (
    <DashboardLayout>
      <div className="mx-auto max-w-2xl space-y-3.5">
        <div className={cn('flex items-center justify-between gap-2')}>
          {back}
          <Button variant="outline" className="h-11 min-h-11 px-3" onClick={reload} aria-label={t('refresh')}>
            <RefreshCw size={18} className={wo.loading ? 'animate-spin' : undefined} aria-hidden="true" />
          </Button>
        </div>
        {body}
      </div>
    </DashboardLayout>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  if (!value) return null;
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap break-words"><bdi dir="auto">{value}</bdi></dd>
    </div>
  );
}
