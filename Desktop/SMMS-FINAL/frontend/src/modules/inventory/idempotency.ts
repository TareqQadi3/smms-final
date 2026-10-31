import { useCallback, useRef } from 'react';

/** RFC 4122 v4 id; crypto.randomUUID where present, getRandomValues/Math.random fallback. */
export function newIdempotencyKey(): string {
  const c = typeof globalThis !== 'undefined' ? (globalThis.crypto as Crypto | undefined) : undefined;
  if (c?.randomUUID) return c.randomUUID();
  const b = new Uint8Array(16);
  if (c?.getRandomValues) c.getRandomValues(b);
  else for (let i = 0; i < 16; i++) b[i] = Math.floor(Math.random() * 256);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * One Idempotency-Key per user intent. `keyFor(payload)` returns the same key while the payload is
 * unchanged (a retry after a network error or 409/5xx must reuse it so the server can replay), and a
 * fresh key when the payload changed (the server answers 422 for a reused key with another body).
 * Call `done()` after a successful call so the next intent gets a new key.
 */
export function useIdempotencyKey() {
  const last = useRef<{ fingerprint: string; key: string } | null>(null);
  const keyFor = useCallback((payload: unknown) => {
    const fingerprint = JSON.stringify(payload ?? null);
    if (!last.current || last.current.fingerprint !== fingerprint) {
      last.current = { fingerprint, key: newIdempotencyKey() };
    }
    return last.current.key;
  }, []);
  const done = useCallback(() => {
    last.current = null;
  }, []);
  return { keyFor, done };
}

export const idempotencyHeaders = (key: string) => ({ headers: { 'Idempotency-Key': key } });
