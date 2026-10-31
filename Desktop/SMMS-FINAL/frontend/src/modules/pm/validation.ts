import { todayIso } from '@/modules/assets/lib';
import type { PlanFormValues } from './types';

export type PlanErrors = Partial<Record<keyof PlanFormValues, string>>;

type Msg = (
  key: 'vAssetRequired' | 'vControlRoomRequired' | 'vTitleRequired' | 'vTooLong' | 'vInterval' | 'vLead' | 'vStartRequired' | 'vNextDuePast',
  p?: Record<string, number>
) => string;

/** Mirrors CreatePlanRequest / UpdatePlanRequest; the server stays the authority. */
export function validatePlan(v: PlanFormValues, mode: 'create' | 'edit', msg: Msg): PlanErrors {
  const e: PlanErrors = {};
  if (mode === 'create' && !v.assetId) e.assetId = msg('vAssetRequired');
  if (!v.controlRoomId) e.controlRoomId = msg('vControlRoomRequired');
  if (!v.title.trim()) e.title = msg('vTitleRequired');
  else if (v.title.trim().length > 300) e.title = msg('vTooLong', { n: 300 });
  if (v.instructions.trim().length > 5000) e.instructions = msg('vTooLong', { n: 5000 });
  const interval = Number(v.frequencyInterval);
  if (v.frequencyInterval.trim() === '' || !Number.isInteger(interval) || interval < 1 || interval > 366) e.frequencyInterval = msg('vInterval');
  if (v.leadDays.trim() !== '') {
    const lead = Number(v.leadDays);
    if (!Number.isInteger(lead) || lead < 0 || lead > 60) e.leadDays = msg('vLead');
  }
  if (mode === 'create' && !v.startDate) e.startDate = msg('vStartRequired');
  if (mode === 'edit' && v.nextDueDate && v.nextDueDate < todayIso()) e.nextDueDate = msg('vNextDuePast');
  return e;
}
