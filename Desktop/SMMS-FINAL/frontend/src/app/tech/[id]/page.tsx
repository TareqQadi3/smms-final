'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { AlertTriangle, ArrowRight, ArrowLeft, Clock, Lock, MapPin, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ErrorState, LoadingState } from '@/components/common';
import { ActionsPanel } from '@/components/tech/ActionsPanel';
import { AssetPanel } from '@/components/tech/AssetPanel';
import { MaterialsPanel } from '@/components/tech/MaterialsPanel';
import { NotesPanel } from '@/components/tech/NotesPanel';
import { PendingActions } from '@/components/tech/PendingActions';
import { PhotosPanel } from '@/components/tech/PhotosPanel';
import { Section } from '@/components/tech/Section';
import { TimelinePanel } from '@/components/tech/TimelinePanel';
import { PriorityBadge, StatusBadge } from '@/components/tech/badges';
import { useQueueEvents, useResource } from '@/components/tech/hooks';
import { useTech } from '@/components/tech/TechContext';
import { useModuleT } from '@/lib/module-i18n';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import { formatDate, cn } from '@/lib/utils';
import { techApi } from '@/modules/tech/api';
import { describeError } from '@/modules/tech/errors';
import { isOverdue, relativeTime } from '@/modules/tech/format';
import { techDict } from '@/modules/tech/i18n';

export default function TechDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Array.isArray(params.id) ? params.id[0] : params.id;
  const { t, lang, isAr } = useModuleT(techDict);
  const { frameTick } = useTech();
  const { items } = useOfflineQueue();
  const [matKey, setMatKey] = useState(0);

  const wo = useResource(`wo:${id}`, () => techApi.get(id).then((r) => r.data), [id]);
  const history = useResource(`hist:${id}`, () => techApi.history(id).then((r) => r.data), [id]);
  const files = useResource(`att:${id}`, () => techApi.attachments(id).then((r) => r.data), [id]);

  const reloadAll = useCallback(() => {
    wo.reload(); history.reload(); files.reload();
    setMatKey((k) => k + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wo.reload, history.reload, files.reload]);

  useEffect(() => { if (frameTick > 0) reloadAll(); }, [frameTick, reloadAll]);
  useQueueEvents((e) => { if (e.item.workOrderId === id) reloadAll(); });

  const Back = isAr ? ArrowRight : ArrowLeft;
  const backLink = (
    <Link href="/tech" className="inline-flex min-h-11 items-center gap-1.5 text-sm font-medium text-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
      <Back size={18} aria-hidden="true" /> {t('backToQueue')}
    </Link>
  );

  if (wo.data === undefined && wo.loading) return <div className="space-y-3">{backLink}<LoadingState rows={5} /></div>;

  if (wo.data === undefined) {
    const failure = describeError(wo.error, t);
    return (
      <div className="space-y-3">
        {backLink}
        <ErrorState title={failure.status === 404 ? t('detailNotFound') : t('detailLoadError')} message={failure.status === 404 ? '' : failure.text} onRetry={failure.status === 404 ? undefined : reloadAll} />
      </div>
    );
  }

  const w = wo.data;
  const overdue = !w.slaPaused && (w.slaBreached || isOverdue(w.slaDeadline));
  const hasAfter = (files.data ?? []).some((a) => a.photoPhase === 'after')
    || items.some((i) => i.workOrderId === id && i.kind === 'photo' && i.meta?.phase === 'after');

  const field = (label: string, value: React.ReactNode) => value ? (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm"><bdi dir="auto">{value}</bdi></dd>
    </div>
  ) : null;

  return (
    <div className="space-y-3.5">
      <div className="flex items-center justify-between gap-2">
        {backLink}
        <Button variant="outline" className="h-11 min-h-11 px-3" onClick={reloadAll} aria-label={t('refresh')}>
          <RefreshCw size={18} className={wo.loading ? 'animate-spin' : undefined} aria-hidden="true" />
        </Button>
      </div>

      {wo.staleSince && (
        <p role="status" className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          {t('cachedNotice', { time: formatDate(wo.staleSince, lang, true) })}
        </p>
      )}

      <header className={cn('rounded-xl border bg-card p-3.5 shadow-sm', w.isEmergency ? 'border-red-400 ring-1 ring-red-300' : 'border-border')}>
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
        <h2 className="mt-2 text-lg font-bold leading-snug">{w.title}</h2>
        <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          {w.locationDetail && <span className="inline-flex items-center gap-1"><MapPin size={13} aria-hidden="true" />{w.locationDetail}</span>}
          <span className={cn('inline-flex items-center gap-1', overdue && 'font-semibold text-red-600')}>
            <Clock size={13} aria-hidden="true" />
            {w.slaPaused ? t('slaPaused') : w.slaDeadline ? t(overdue ? 'slaOverdue' : 'slaDue', { time: relativeTime(w.slaDeadline, lang) }) : t('slaNone')}
          </span>
        </div>
        {w.locked && (
          <p role="note" className="mt-2 flex items-start gap-1.5 rounded-lg bg-amber-50 px-2.5 py-2 text-xs text-amber-900">
            <Lock size={14} className="mt-0.5 shrink-0" aria-hidden="true" /> {t('detailLocked')}
          </p>
        )}
      </header>

      <PendingActions workOrderId={id} />

      <ActionsPanel wo={w} hasAfterPhoto={hasAfter} onChanged={reloadAll} />

      <Section title={t('secDetails')}>
        <dl className="space-y-2.5">
          <div>
            <dt className="text-xs text-muted-foreground">{t('fieldDescription')}</dt>
            <dd className="whitespace-pre-wrap break-words text-sm"><bdi dir="auto">{w.description || t('noDescription')}</bdi></dd>
          </div>
          {field(t('fieldLocation'), w.locationDetail)}
          {field(t('fieldCenter'), w.center ? (isAr && w.center.nameAr ? w.center.nameAr : w.center.name) : null)}
          {field(t('fieldControlRoom'), w.controlRoom ? (isAr && w.controlRoom.nameAr ? w.controlRoom.nameAr : w.controlRoom.name) : null)}
          {field(t('fieldRequester'), w.requestedBy ? (isAr && w.requestedBy.fullNameAr ? w.requestedBy.fullNameAr : w.requestedBy.fullName) : null)}
          {field(t('fieldCreated'), formatDate(w.createdAt, lang, true))}
          {field(t('fieldSla'), w.slaDeadline ? formatDate(w.slaDeadline, lang, true) : null)}
          {field(t('fieldWarehouse'), w.warehouseNotes)}
          {field(t('fieldReport'), w.techReport)}
          {field(t('fieldRejection'), w.rejectionReason)}
        </dl>
      </Section>

      <PhotosPanel wo={w} attachments={files.data} loading={files.loading} failed={!!files.error} onChanged={reloadAll} />
      <NotesPanel wo={w} history={history.data ?? []} onChanged={reloadAll} />
      <MaterialsPanel wo={w} refreshKey={matKey} onChanged={reloadAll} />
      <AssetPanel wo={w} />
      <TimelinePanel history={history.data} failed={!!history.error} />
    </div>
  );
}
