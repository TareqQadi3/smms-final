'use client';

import { useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { newIdempotencyKey, workflowApi } from '@/modules/workflow/api';
import { describeError, isConflict } from '@/modules/workflow/errors';
import { canAddNote } from '@/modules/workflow/permissions';
import type { WorkOrderDetail } from '@/modules/workflow/types';

/** Add a note to the work order history (technician: only when assigned and active; the server decides). */
export function NotesPanel({
  wo, user, onAdded, onConflict,
}: {
  wo: WorkOrderDetail;
  user: { id?: string; role?: string } | null;
  onAdded: () => void;
  onConflict?: () => void;
}) {
  const { t } = useModuleT(workflowDict);
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  const keyRef = useRef(newIdempotencyKey()); // a retry of the same text reuses the key

  if (!canAddNote(user?.role, user?.id, wo)) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const clean = text.trim();
    setOk('');
    if (!clean) { setError(t('noteRequired')); return; }
    if (clean.length > 2000) { setError(t('noteTooLong')); return; }
    setBusy(true); setError('');
    try {
      await workflowApi.addNote(wo.id, clean, keyRef.current);
      keyRef.current = newIdempotencyKey();
      setText(''); setOk(t('noteAdded')); onAdded();
    } catch (err) {
      setError(describeError(err, t));
      if (isConflict(err)) onConflict?.();
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{t('notes')}</CardTitle></CardHeader>
      <CardContent>
        <form onSubmit={submit} className="space-y-2" noValidate>
          <FormField label={t('noteLabel')} error={error || undefined}>
            <Textarea rows={3} maxLength={2000} value={text} onChange={(e) => setText(e.target.value)} placeholder={t('notePlaceholder')} />
          </FormField>
          <div className="flex items-center gap-3">
            <Button type="submit" size="sm" disabled={busy}>{busy ? t('working') : t('saveNote')}</Button>
            {ok && <span role="status" className="text-sm text-green-700">{ok}</span>}
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
