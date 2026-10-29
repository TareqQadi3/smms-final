import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import { newIdempotencyKey, useIdempotencyKey } from './idempotency';

describe('idempotency key', () => {
  it('generates UUID-shaped keys accepted by the backend pattern', () => {
    const k = newIdempotencyKey();
    expect(k).toMatch(/^[A-Za-z0-9-]{8,64}$/);
    expect(newIdempotencyKey()).not.toBe(k);
  });

  it('reuses the key for an unchanged payload (retry) and renews it for a new payload or after success', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const a = result.current.keyFor({ qty: 1 });
    expect(result.current.keyFor({ qty: 1 })).toBe(a); // retry
    const b = result.current.keyFor({ qty: 2 }); // user changed the intent
    expect(b).not.toBe(a);
    result.current.done(); // success
    expect(result.current.keyFor({ qty: 2 })).not.toBe(b);
  });
});
