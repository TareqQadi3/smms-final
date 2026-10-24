'use client';

import { useState } from 'react';
import { Send } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { techActions } from '@/modules/tech/api';
import { techDict } from '@/modules/tech/i18n';
import { canAddNote } from '@/modules/tech/rules';
import type { HistoryEntry, TechWorkOrder } from '@/modules/tech/types';
import { ActionResult } from './ActionResult';
import { Section } from './Section';
import { useActionRunner } from './useActionRunner';

const NOTE_MAX = 2000;

/** POST /work-orders/{id}/notes: free-text work notes (queued when offline). Shows the notes from the history. */
export function NotesPanel({ wo, history, onChanged }: { wo: TechWorkOrder; history: HistoryEntry[]; onChanged: () => void }) {
  const { t, lang } = useModuleT(techDict);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | undefined>();
  const { run, busy, result } = useActionRunner(onChanged);
  const notes = history.filter((h) => h.stage === 'note').reverse();
  const allowed = canAddNote(wo.status);

  const submit = async () => {
    const clean = text.trim();
    if (!clean) { setError(t('noteRequired')); return; }
    if (clean.length > NOTE_MAX) { setError(t('noteTooLong', { max: NOTE_MAX })); return; }
    setError(undefined);
    const outcome = await run(techActions.note(wo, clean));
    if (outcome && outcome.state !== 'failed') setText('');
  };

  return (
    <Section title={t('secNotes')}>
      {allowed ? (
        <div className="space-y-2">
          <FormField label={t('noteLabel')} error={error}>
            <Textarea
              rows={3}
              value={text}
              placeholder={t('notePlaceholder')}
              onChange={(e) => { setText(e.target.value); if (error) setError(undefined); }}
              disabled={busy}
            />
          </FormField>
          <Button className="h-11 min-h-11 w-full" disabled={busy} onClick={() => void submit()}>
            <Send size={16} aria-hidden="true" /> {busy ? t('sending') : t('noteSend')}
          </Button>
          <ActionResult result={result} />
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{t('noteUnavailable')}</p>
      )}
      <ul className="mt-3 space-y-2">
        {notes.length === 0 && <li className="text-sm text-muted-foreground">{t('noteNone')}</li>}
        {notes.map((n) => (
          <li key={n.id} className="rounded-lg bg-muted/50 p-2.5 text-sm">
            <p className="whitespace-pre-wrap break-words"><bdi dir="auto">{n.note}</bdi></p>
            <p className="mt-1 text-xs text-muted-foreground">{n.actor?.fullName} - {formatDate(n.createdAt, lang, true)}</p>
          </li>
        ))}
      </ul>
    </Section>
  );
}
