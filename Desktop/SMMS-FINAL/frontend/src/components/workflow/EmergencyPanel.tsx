'use client';

import { useState } from 'react';
import { Siren } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import { canClearEmergency, canDeclareEmergency } from '@/modules/workflow/permissions';
import type { WorkOrderDetail } from '@/modules/workflow/types';
import { ReasonDialog } from './ReasonDialog';

/**
 * Emergency state of a work order with declare / clear actions. Buttons follow the server roles
 * (declare: control room, maintenance manager, hospital supervisor, admin; clear: all but control
 * room); the server enforces them regardless. Both actions require a reason in a confirm dialog.
 */
export function EmergencyPanel({
  wo, role, onChanged, onConflict,
}: {
  wo: WorkOrderDetail;
  role?: string;
  onChanged: () => void;
  onConflict?: () => void;
}) {
  const { t, lang } = useModuleT(workflowDict);
  const [dialog, setDialog] = useState<'declare' | 'clear' | null>(null);
  const [notice, setNotice] = useState('');
  const declare = canDeclareEmergency(role, wo);
  const clear = canClearEmergency(role, wo);

  if (!wo.isEmergency && !declare) return null;

  return (
    <Card className={wo.isEmergency ? 'border-red-400 bg-red-50 dark:bg-red-950/30' : undefined}>
      <CardContent className="space-y-2 py-3">
        {wo.isEmergency && (
          <div className="flex items-center gap-2 text-red-700 dark:text-red-300" role="status">
            <Siren size={18} aria-hidden />
            <span className="font-semibold">{t('emergencyActive')}</span>
            {wo.emergencyDeclaredAt && (
              <span className="text-xs">({t('emergencyDeclaredAt')} {formatDate(wo.emergencyDeclaredAt, lang, true)})</span>
            )}
          </div>
        )}
        {notice && <p role="status" className="text-sm text-green-700">{notice}</p>}
        <div className="flex flex-wrap gap-2">
          {declare && (
            <Button size="sm" variant="destructive" className="gap-1.5" onClick={() => setDialog('declare')}>
              <Siren size={14} aria-hidden /> {t('declareEmergency')}
            </Button>
          )}
          {clear && (
            <Button size="sm" variant="outline" onClick={() => setDialog('clear')}>{t('clearEmergency')}</Button>
          )}
        </div>
      </CardContent>

      <ReasonDialog
        open={dialog === 'declare'}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t('declareEmergencyTitle')}
        description={t('declareEmergencyDesc')}
        confirmLabel={t('declareEmergency')}
        destructive
        onConflict={() => { setDialog(null); onConflict?.(); }}
        onSubmit={async (reason) => {
          await workflowApi.declareEmergency(wo.id, reason);
          setDialog(null); setNotice(t('emergencyDeclared')); onChanged();
        }}
      />
      <ReasonDialog
        open={dialog === 'clear'}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t('clearEmergencyTitle')}
        description={t('clearEmergencyDesc')}
        confirmLabel={t('clearEmergency')}
        onConflict={() => { setDialog(null); onConflict?.(); }}
        onSubmit={async (reason) => {
          await workflowApi.clearEmergency(wo.id, reason);
          setDialog(null); setNotice(t('emergencyCleared')); onChanged();
        }}
      />
    </Card>
  );
}
