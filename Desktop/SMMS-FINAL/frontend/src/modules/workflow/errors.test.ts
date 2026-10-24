import { describe, expect, it } from 'vitest';
import { translate } from '@/lib/module-i18n';
import { workflowDict, formatDuration } from './i18n';
import { describeError, isConflict } from './errors';

const err = (status: number, message?: string) => ({ response: { status, data: message ? { message } : {} } });
const t = (k: Parameters<typeof translate<keyof typeof workflowDict.ar>>[2]) => translate(workflowDict, 'en', k);

describe('describeError', () => {
  it('409 is the optimistic-lock reload message', () => {
    expect(isConflict(err(409))).toBe(true);
    expect(describeError(err(409, 'x'), t)).toMatch(/Reload and try again/);
    expect(translate(workflowDict, 'ar', 'conflictReload')).toMatch(/أعد تحميل/);
  });
  it('403 and 404 use fixed texts; 422 keeps the server explanation', () => {
    expect(describeError(err(403), t)).toBe(t('errForbidden'));
    expect(describeError(err(404), t)).toBe(t('errNotFound'));
    expect(describeError(err(422, 'Cannot escalate a paused ticket'), t)).toBe('Cannot escalate a paused ticket');
    expect(describeError(new Error('net'), t)).toBe(t('errGeneric'));
  });
});

describe('formatDuration', () => {
  it('formats days, hours and minutes in both languages', () => {
    expect(formatDuration(90061, false)).toBe('1 d 1 h 1 min');
    expect(formatDuration(0, false)).toBe('0 min');
    expect(formatDuration(3600, true)).toBe('1 ساعة');
  });
});
