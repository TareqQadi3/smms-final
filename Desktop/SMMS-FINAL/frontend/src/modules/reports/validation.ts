import { daysBetween } from '@/modules/assets/lib';

export const MAX_RANGE_DAYS = 366;
const EARLIEST = '2000-01-01';
const ISO = /^\d{4}-\d{2}-\d{2}$/;

export type RangeErrorKey = 'rangeRequired' | 'rangeInvalid' | 'rangeOrder' | 'rangeTooLong' | 'rangeTooEarly';

/** Mirrors ReportRange on the server (both ends inclusive, at most 366 days). Returns an error key or null. */
export function validateRange(from: string, to: string): RangeErrorKey | null {
  if (!from || !to) return 'rangeRequired';
  if (!ISO.test(from) || !ISO.test(to) || Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) return 'rangeInvalid';
  if (from > to) return 'rangeOrder';
  if (from < EARLIEST) return 'rangeTooEarly';
  if (daysBetween(from, to) > MAX_RANGE_DAYS) return 'rangeTooLong';
  return null;
}
