/**
 * Idempotency-Key helpers. The backend accepts 8-64 characters; material requests allow letters,
 * digits and '-' only, so a UUID (letters, digits, '-') is valid everywhere.
 */

const KEY_PATTERN = /^[A-Za-z0-9-]{8,64}$/;

function randomBytes(n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
  if (c && typeof c.getRandomValues === 'function') {
    c.getRandomValues(bytes);
  } else {
    for (let i = 0; i < n; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  return bytes;
}

/** A fresh RFC 4122 v4 UUID. One key per LOGICAL action: retries must reuse it, never mint a new one. */
export function newIdempotencyKey(): string {
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  const b = randomBytes(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function isValidIdempotencyKey(key: string): boolean {
  return KEY_PATTERN.test(key);
}

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';
