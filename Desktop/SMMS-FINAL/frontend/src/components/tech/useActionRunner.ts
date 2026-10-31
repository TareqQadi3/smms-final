'use client';

import { useCallback, useRef, useState } from 'react';
import { useModuleT } from '@/lib/module-i18n';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import type { ActionOutcome, ActionSpec } from '@/lib/offline/queue';
import { describeError, type DescribedError } from '@/modules/tech/errors';
import { techDict } from '@/modules/tech/i18n';

export type RunResult =
  | { kind: 'ok'; text: string }
  | { kind: 'queued'; text: string }
  | { kind: 'error'; error: DescribedError };

/**
 * Sends an action through the offline queue and tells the user what happened: done, saved on the
 * device for later, or refused by the server (403/404/409/422 with their own wording).
 * Ignores a second call while one is running (no double submit).
 */
export function useActionRunner(onDone?: () => void) {
  const { t } = useModuleT(techDict);
  const { enqueue } = useOfflineQueue();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<RunResult | null>(null);
  const running = useRef(false);
  const doneRef = useRef(onDone);
  doneRef.current = onDone;

  const run = useCallback(
    async (spec: ActionSpec): Promise<ActionOutcome | null> => {
      if (running.current) return null;
      running.current = true;
      setBusy(true);
      setResult(null);
      try {
        const outcome = await enqueue(spec);
        if (outcome.state === 'done') {
          setResult({ kind: 'ok', text: t('actSent') });
          doneRef.current?.();
        } else if (outcome.state === 'queued') {
          setResult({ kind: 'queued', text: t('actQueued') });
        } else {
          setResult({ kind: 'error', error: describeError(outcome.error, t) });
          // 404/409/422 usually mean the ticket moved on: show the server state
          doneRef.current?.();
        }
        return outcome;
      } finally {
        running.current = false;
        setBusy(false);
      }
    },
    [enqueue, t]
  );

  return { run, busy, result, clear: () => setResult(null) };
}
