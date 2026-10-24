'use client';

import { useEffect, useId, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { describeError, isConflict } from '@/modules/workflow/errors';

/**
 * Confirmation dialog that requires a written reason (5..1000 characters, the backend limits).
 * `onSubmit` performs the request; if it throws, the localized error is shown and the dialog stays
 * open. A 409 (optimistic lock) additionally calls `onConflict` so the page can offer a reload.
 */
export function ReasonDialog({
  open, onOpenChange, title, description, confirmLabel, destructive, onSubmit, onConflict,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  confirmLabel: string;
  destructive?: boolean;
  onSubmit: (reason: string) => Promise<void>;
  onConflict?: () => void;
}) {
  const { t, dir } = useModuleT(workflowDict);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const formId = useId();

  useEffect(() => { if (open) { setReason(''); setError(''); setBusy(false); } }, [open]);

  const clean = reason.trim();
  const tooShort = clean.length < 5;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (tooShort) { setError(t('reasonTooShort')); return; }
    setBusy(true);
    setError('');
    try {
      await onSubmit(clean);
    } catch (err) {
      setError(describeError(err, t));
      if (isConflict(err)) onConflict?.();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir} showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <form id={formId} onSubmit={submit} className="space-y-3" noValidate>
          <FormField label={t('reason')} required hint={t('reasonHint')} error={error || undefined}>
            <Textarea rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
          </FormField>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>{t('cancel')}</Button>
          <Button type="submit" form={formId} variant={destructive ? 'destructive' : 'default'} disabled={busy}>
            {busy ? t('working') : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
