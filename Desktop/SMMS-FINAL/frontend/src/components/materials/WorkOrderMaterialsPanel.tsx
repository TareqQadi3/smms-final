'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { can } from '@/modules/inventory/permissions';
import { Banner, DataState, localName, useApiData } from '@/modules/inventory/shared';
import { materialsApi } from '@/modules/materials/api';
import { CreateMrDialog } from '@/modules/materials/components/CreateMrDialog';
import { MrStatusBadge } from '@/modules/materials/components/MrStatusBadge';
import { materialsDict } from '@/modules/materials/i18n';

const CAN_REQUEST_STATUSES = ['in_progress', 'waiting_materials'];

/**
 * Materials section of a work order: lists its material requests and (for technician, control room,
 * maintenance manager) creates new ones. The server decides who may request and when (a request needs
 * the work order to be in_progress or waiting_materials); `workOrderStatus` only hides the button early.
 * Embedded by the work-order detail page.
 */
export function WorkOrderMaterialsPanel({
  workOrderId,
  workOrderStatus,
  centerId,
  className,
}: {
  workOrderId: string;
  workOrderStatus?: string;
  centerId?: string;
  className?: string;
}) {
  const { t, lang } = useModuleT(materialsDict);
  const role = useAuthStore((s) => s.user?.role);
  const allowed = can.readMr(role);
  const { data, loading, error, reload } = useApiData(() => materialsApi.listForWorkOrder(workOrderId), [workOrderId], allowed);
  const [dialog, setDialog] = useState(false);
  const [created, setCreated] = useState<string | null>(null);
  if (!allowed) return <PermissionDenied />;
  const rows = data ?? [];
  const statusOk = !workOrderStatus || CAN_REQUEST_STATUSES.includes(workOrderStatus);
  const canCreate = can.createMr(role) && statusOk;
  const known = Array.from(new Set(rows.map((r) => r.warehouseId).filter((x): x is string => !!x)));

  return (
    <Card className={className} aria-label={t('materialsPanelTitle')}>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle>{t('materialsPanelTitle')}</CardTitle>
        {canCreate && (
          <Button size="sm" onClick={() => setDialog(true)}>
            <Plus size={14} aria-hidden="true" />
            {t('newMr')}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        {created && <Banner kind="success">{t('mrCreated', { n: created })}</Banner>}
        {can.createMr(role) && !statusOk && <p className="text-xs text-muted-foreground">{t('mrNeedsActiveWo')}</p>}
        <DataState loading={loading} error={error} onRetry={reload} empty={rows.length === 0} emptyTitle={t('noMrForWo')}>
          <ul className="divide-y rounded-md border text-sm">
            {rows.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 p-2">
                <Link href={`/material-requests/${r.id}`} className="font-mono text-primary hover:underline" dir="ltr">{r.requestNumber}</Link>
                <span className="text-xs text-muted-foreground">{localName(r.requestedBy, lang === 'ar')} · {formatDate(r.createdAt, lang)}</span>
                <MrStatusBadge status={r.status} />
              </li>
            ))}
          </ul>
        </DataState>
      </CardContent>
      <CreateMrDialog
        open={dialog}
        onOpenChange={setDialog}
        workOrderId={workOrderId}
        centerId={centerId}
        knownWarehouseIds={known}
        onCreated={(mr) => {
          setCreated(mr.requestNumber);
          reload();
        }}
      />
    </Card>
  );
}
