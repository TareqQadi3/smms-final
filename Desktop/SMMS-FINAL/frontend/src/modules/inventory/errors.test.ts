import { describe, expect, it } from 'vitest';
import { mapApiError } from './errors';
import { httpError } from './testUtils';

describe('mapApiError', () => {
  it('maps statuses to kinds', () => {
    expect(mapApiError(httpError(403), 'en').kind).toBe('forbidden');
    expect(mapApiError(httpError(404), 'en').kind).toBe('notFound');
    expect(mapApiError(httpError(409), 'en').kind).toBe('conflict');
    expect(mapApiError(httpError(422), 'en').kind).toBe('unprocessable');
    expect(mapApiError(httpError(400), 'en').kind).toBe('badRequest');
    expect(mapApiError(httpError(500), 'en').kind).toBe('server');
    expect(mapApiError(new Error('x'), 'en').kind).toBe('network');
  });

  it('appends the server detail for 409 and 422 and localizes the lead', () => {
    const m = mapApiError(httpError(422, 'Over-receipt on line 1'), 'en');
    expect(m.message).toContain('422');
    expect(m.message).toContain('Over-receipt on line 1');
    expect(mapApiError(httpError(409, 'Insufficient stock'), 'ar').message).toContain('تعارض');
  });

  it('does not echo server detail for 500', () => {
    expect(mapApiError(httpError(500, 'stack trace'), 'en').message).not.toContain('stack trace');
  });
});
