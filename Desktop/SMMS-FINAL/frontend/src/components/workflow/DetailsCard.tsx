'use client';

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useT } from '@/lib/i18n';
import { formatDate } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import type { WorkOrderDetail } from '@/modules/workflow/types';
import { AssetLink } from './AssetLink';

/** Main details card of the work order (content unchanged from the old page, plus asset link). */
export function DetailsCard({ wo }: { wo: WorkOrderDetail }) {
  const { lang } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{isAr ? 'التفاصيل' : 'Details'}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        {wo.description && (
          <div>
            <p className="mb-1 text-xs font-medium text-muted-foreground">{tr.description}</p>
            <p className="rounded-lg bg-muted/50 p-3 text-sm">{wo.description}</p>
          </div>
        )}
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <p className="text-xs font-medium text-muted-foreground">{tr.subType}</p>
            <p className="font-medium capitalize">{wo.subType}</p>
          </div>
          {wo.locationDetail && (
            <div>
              <p className="text-xs font-medium text-muted-foreground">{tr.location}</p>
              <p className="font-medium">{wo.locationDetail}</p>
            </div>
          )}
          <div>
            <p className="text-xs font-medium text-muted-foreground">{tr.requestedBy}</p>
            <p className="font-medium">{wo.requestedBy?.fullName || '-'}</p>
          </div>
          <div>
            <p className="text-xs font-medium text-muted-foreground">{tr.assignedTo}</p>
            <p className="font-medium">{wo.assignedTo?.fullName || '-'}</p>
          </div>
          {wo.createdAt && (
            <div>
              <p className="text-xs font-medium text-muted-foreground">{isAr ? 'تاريخ الإنشاء' : 'Created'}</p>
              <p className="font-medium">{formatDate(wo.createdAt, lang, true)}</p>
            </div>
          )}
          {wo.slaDeadline && (
            <div>
              <p className="text-xs font-medium text-muted-foreground">{tr.slaDeadline}</p>
              <p className={`font-medium ${wo.slaBreached ? 'text-red-600' : ''}`}>
                {formatDate(wo.slaDeadline, lang, true)}
                {wo.slaBreached && ' ⚠'}
              </p>
            </div>
          )}
          {(wo.asset || wo.assetId) && <div className="col-span-2"><AssetLink wo={wo} /></div>}
          {wo.techReport && (
            <div className="col-span-2">
              <p className="text-xs font-medium text-muted-foreground">{isAr ? 'تقرير الفني' : 'Tech Report'}</p>
              <p className="text-sm font-medium">{wo.techReport}</p>
            </div>
          )}
          {wo.warehouseNotes && (
            <div className="col-span-2">
              <p className="text-xs font-medium text-muted-foreground">{isAr ? 'ملاحظات المستودع' : 'Warehouse Notes'}</p>
              <p className="text-sm font-medium">{wo.warehouseNotes}</p>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
