'use client';

import { useState } from 'react';
import { CheckCircle2, PackageSearch, PlayCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import { techActions } from '@/modules/tech/api';
import { techDict } from '@/modules/tech/i18n';
import { technicianTransitions } from '@/modules/tech/rules';
import type { TechTransition, TechWorkOrder } from '@/modules/tech/types';
import { ActionResult } from './ActionResult';
import { Section } from './Section';
import { useActionRunner } from './useActionRunner';

export const REPORT_MAX = 2000;
const MIN_LEN: Partial<Record<TechTransition, number>> = { completed: 5, waiting_materials: 3 };

/**
 * Status actions the technician role may take. Only server-allowed transitions are offered
 * (assigned -> in_progress; in_progress -> waiting_materials | completed); the server still has the
 * last word and its 403/409/422 answers are shown as they are.
 */
export function ActionsPanel({ wo, hasAfterPhoto, onChanged }: { wo: TechWorkOrder; hasAfterPhoto: boolean; onChanged: () => void }) {
  const { t, dir } = useModuleT(techDict);
  const { items } = useOfflineQueue();
  const { run, busy, result } = useActionRunner(onChanged);
  const [dialog, setDialog] = useState<TechTransition | null>(null);
  const [text, setText] = useState('');
  const [touched, setTouched] = useState(false);

  const allowed = technicianTransitions(wo.status);
  const inFlight = items.some((i) => i.workOrderId === wo.id && i.kind === 'transition' && i.status !== 'failed');
  const disabled = busy || inFlight;

  const min = dialog ? (MIN_LEN[dialog] ?? 1) : 1;
  const trimmed = text.trim();
  const error = !touched ? undefined
    : trimmed.length < min ? t('reportRequired')
    : trimmed.length > REPORT_MAX ? t('reportTooLong', { max: REPORT_MAX }) : undefined;

  const open = (tr: TechTransition) => { setDialog(tr); setText(''); setTouched(false); };

  const submitReport = async () => {
    setTouched(true);
    if (!dialog || trimmed.length < min || trimmed.length > REPORT_MAX) return;
    const outcome = await run(techActions.transition(wo, dialog, trimmed));
    if (outcome && outcome.state !== 'failed') setDialog(null);
  };

  return (
    <Section title={t('secActions')}>
      <div className="space-y-2.5">
        {allowed.includes('in_progress') && (
          <Button className="h-12 min-h-12 w-full text-base" disabled={disabled} onClick={() => void run(techActions.transition(wo, 'in_progress'))}>
            <PlayCircle size={20} aria-hidden="true" /> {busy ? t('sending') : t('actStart')}
          </Button>
        )}
        {allowed.includes('completed') && (
          <Button className="h-12 min-h-12 w-full text-base" disabled={disabled} onClick={() => open('completed')}>
            <CheckCircle2 size={20} aria-hidden="true" /> {t('actComplete')}
          </Button>
        )}
        {allowed.includes('waiting_materials') && (
          <Button variant="outline" className="h-12 min-h-12 w-full text-base" disabled={disabled} onClick={() => open('waiting_materials')}>
            <PackageSearch size={20} aria-hidden="true" /> {t('actWaitingMaterials')}
          </Button>
        )}
        {allowed.length === 0 && (
          <p className="text-sm text-muted-foreground">
            {wo.status === 'waiting_materials' || wo.status === 'waiting_approval' ? t('actWaitingHint')
              : wo.status === 'completed' ? t('actDoneHint') : t('actNoneAvailable')}
          </p>
        )}
        <ActionResult result={dialog ? null : result} />
      </div>

      <Dialog open={dialog !== null} onOpenChange={(o) => { if (!o && !busy) setDialog(null); }}>
        <DialogContent dir={dir}>
          <DialogHeader>
            <DialogTitle>{dialog === 'completed' ? t('dlgCompleteTitle') : t('dlgWaitingTitle')}</DialogTitle>
            <DialogDescription>{dialog === 'completed' ? t('dlgCompleteBody') : t('dlgWaitingBody')}</DialogDescription>
          </DialogHeader>
          {dialog === 'completed' && !hasAfterPhoto && (
            <p role="note" className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">{t('noAfterPhotoHint')}</p>
          )}
          <FormField label={t('fieldReportInput')} required error={error}>
            <Textarea
              rows={5}
              value={text}
              maxLength={REPORT_MAX + 200}
              placeholder={t('reportPlaceholder')}
              onChange={(e) => { setText(e.target.value); setTouched(true); }}
              disabled={busy}
            />
          </FormField>
          <ActionResult result={dialog ? result : null} />
          <DialogFooter>
            <Button variant="outline" className="h-11 min-h-11" disabled={busy} onClick={() => setDialog(null)}>{t('cancel')}</Button>
            <Button className="h-11 min-h-11" disabled={busy} onClick={() => void submitReport()}>
              {busy ? t('sending') : t('submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Section>
  );
}
