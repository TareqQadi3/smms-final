'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { ArrowRight, PauseCircle, Siren } from 'lucide-react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { workOrderApi } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { ErrorState, LoadingState, PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { deriveActions } from '@/modules/workflow/permissions';
import { parseApiError } from '@/modules/workflow/errors';
import type { HistoryEntry, WorkOrderDetail } from '@/modules/workflow/types';
import { AttachmentsPanel } from '@/components/workflow/AttachmentsPanel';
import { ConfirmationRating } from '@/components/workflow/ConfirmationRating';
import { ConflictBanner } from '@/components/workflow/ConflictBanner';
import { DetailsCard } from '@/components/workflow/DetailsCard';
import { EmergencyPanel } from '@/components/workflow/EmergencyPanel';
import { EscalationPanel } from '@/components/workflow/EscalationPanel';
import { HistoryTimeline } from '@/components/workflow/HistoryTimeline';
import { InfoRequestThread } from '@/components/workflow/InfoRequestThread';
import { NotesPanel } from '@/components/workflow/NotesPanel';
import { WorkOrderActions } from '@/components/workflow/WorkOrderActions';
import { workflowSlots } from '@/components/workflow/slots';
import '@/components/workflow/slotsWiring';

export default function WorkOrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const { t } = useModuleT(workflowDict);
  const router = useRouter();
  const isAr = lang === 'ar';

  const [wo, setWo] = useState<WorkOrderDetail | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadStatus, setLoadStatus] = useState<number | undefined>();
  const [failed, setFailed] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  /** Loads the work order and its history; sub-panels reload through `refreshKey`. */
  const load = useCallback(async () => {
    try {
      const [woRes, histRes] = await Promise.all([workOrderApi.getById(id), workOrderApi.getHistory(id)]);
      setWo(woRes.data);
      setHistory(histRes.data || []);
      setFailed(false); setLoadStatus(undefined);
      setRefreshKey((k) => k + 1);
    } catch (e) {
      setFailed(true); setLoadStatus(parseApiError(e).status);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  /** Optimistic-lock recovery: drop the banner and fetch the current version. */
  const reloadAfterConflict = useCallback(() => { setConflict(false); void load(); }, [load]);
  const changed = useCallback(() => { setConflict(false); void load(); }, [load]);

  const layout = (children: React.ReactNode) => <DashboardLayout>{children}</DashboardLayout>;

  if (loading) return layout(<LoadingState rows={4} />);

  if (!wo) {
    if (loadStatus === 403) return layout(<PermissionDenied />);
    return layout(
      <ErrorState
        title={loadStatus === 404 ? t('errNotFound') : undefined}
        message={loadStatus === 404 ? undefined : t('loadFailed')}
        onRetry={() => { setLoading(true); void load(); }}
      />
    );
  }

  const a = deriveActions(wo, user);
  const Materials = workflowSlots.MaterialsPanel;

  return layout(
    <div className="mx-auto max-w-4xl space-y-5">
      <Button variant="ghost" size="sm" onClick={() => router.back()} className="gap-1.5">
        <ArrowRight size={16} className="rtl:rotate-0 ltr:rotate-180" aria-hidden />
        {isAr ? 'رجوع' : 'Back'}
      </Button>

      {conflict && <ConflictBanner onReload={reloadAfterConflict} />}
      {failed && (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-2 text-sm text-destructive">
          <span>{t('loadFailed')}</span>
          <Button size="sm" variant="outline" onClick={() => void load()}>{t('retry')}</Button>
        </div>
      )}

      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="font-mono text-xs font-semibold text-primary"><bdi dir="ltr">{wo.ticketNumber}</bdi></p>
          <h1 className="mt-1 text-xl font-bold text-foreground">{wo.title}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          <span className={`rounded-full px-3 py-1 text-sm font-medium ${statusColors[wo.status] || 'bg-gray-100'}`}>
            {tStatus(wo.status, lang)}
          </span>
          <span className={`rounded border px-2 py-1 text-sm font-medium ${priorityColors[wo.priority] || ''}`}>
            {tPriority(wo.priority, lang)}
          </span>
          {wo.isEmergency && (
            <span className="inline-flex items-center gap-1 rounded border border-red-400 bg-red-100 px-2 py-1 text-sm font-semibold text-red-800">
              <Siren size={14} aria-hidden /> {t('emergency')}
            </span>
          )}
          {(wo.escalationLevel ?? 0) > 0 && (
            <span className="rounded border border-orange-300 bg-orange-100 px-2 py-1 text-sm font-medium text-orange-800">
              {t('escalationLevel', { n: wo.escalationLevel ?? 0 })}
            </span>
          )}
          {a.infoPending && (
            <span className="rounded border border-amber-300 bg-amber-100 px-2 py-1 text-sm font-medium text-amber-800">
              {t('infoAwaiting')}
            </span>
          )}
          {wo.slaPaused && (
            <span className="inline-flex items-center gap-1 rounded border border-amber-300 bg-amber-100 px-2 py-1 text-sm font-medium text-amber-800">
              <PauseCircle size={14} aria-hidden /> {t('slaPausedBadge')}
            </span>
          )}
          {wo.slaBreached && (
            <span className="rounded border border-red-300 bg-red-100 px-2 py-1 text-sm font-medium text-red-700">⚠ SLA</span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <div className="space-y-5 lg:col-span-2">
          <EmergencyPanel wo={wo} role={user?.role} onChanged={changed} onConflict={() => setConflict(true)} />
          <DetailsCard wo={wo} />
          <InfoRequestThread wo={wo} refreshKey={refreshKey} />
          <ConfirmationRating wo={wo} role={user?.role} refreshKey={refreshKey} />
          {Materials && (
            <Card>
              <CardHeader className="pb-2"><CardTitle className="text-base">{t('materials')}</CardTitle></CardHeader>
              <CardContent>
                <Materials workOrderId={wo.id} status={wo.status} assignedToId={wo.assignedTo?.id ?? null}
                  role={user?.role} onChanged={changed} />
              </CardContent>
            </Card>
          )}
          <AttachmentsPanel workOrderId={wo.id} refreshKey={refreshKey} />
          <NotesPanel wo={wo} user={user} onAdded={changed} onConflict={() => setConflict(true)} />
          <HistoryTimeline history={history} />
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm">{isAr ? 'معلومات الموقع' : 'Location'}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              {wo.center && (
                <div>
                  <p className="text-xs text-muted-foreground">{tr.center}</p>
                  <p className="font-medium">{wo.center.name}</p>
                </div>
              )}
              {wo.controlRoom && (
                <div>
                  <p className="text-xs text-muted-foreground">{tr.controlRoom}</p>
                  <p className="font-medium">{wo.controlRoom.name}</p>
                </div>
              )}
            </CardContent>
          </Card>

          <WorkOrderActions wo={wo} onDone={changed} onConflict={() => setConflict(true)} />
          <EscalationPanel wo={wo} role={user?.role} refreshKey={refreshKey} onChanged={changed} onConflict={() => setConflict(true)} />
        </div>
      </div>
    </div>
  );
}
