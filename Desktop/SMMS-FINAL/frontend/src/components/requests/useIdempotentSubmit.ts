'use client';

import { useCallback, useRef } from 'react';
import { newIdempotencyKey } from '@/lib/offline/idempotency';
import { isRetryable } from '@/modules/requests/errors';

/**
 * One Idempotency-Key per logical submission. The key survives a retryable failure (lost
 * connection, 5xx), so pressing the button again cannot apply the action twice; it is dropped after
 * success or a definitive refusal (4xx), so the next attempt is a new action.
 */
export function useIdempotentSubmit() {
  const key = useRef<string | null>(null);
  const run = useCallback(async <T,>(send: (key: string) => Promise<T>): Promise<T> => {
    if (!key.current) key.current = newIdempotencyKey();
    try {
      const out = await send(key.current);
      key.current = null;
      return out;
    } catch (e) {
      if (!isRetryable(e)) key.current = null;
      throw e;
    }
  }, []);
  const reset = useCallback(() => { key.current = null; }, []);
  return { run, reset };
}
