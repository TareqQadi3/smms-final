import type { RequestWorkOrder } from './types';

export const INFO_REQUESTED = 'info_requested';
const DAY = 86_400_000;
export const RATING_WINDOW_DAYS = 30;

export function needsInfo(wo: RequestWorkOrder): boolean {
  return wo.status === 'under_review' && wo.currentStage === INFO_REQUESTED;
}

export function awaitingConfirmation(wo: RequestWorkOrder): boolean {
  return wo.status === 'completed' && !wo.requesterConfirmation;
}

/** closed, or completed and accepted. The server also enforces the 30-day window and "once". */
export function ratingEligible(wo: RequestWorkOrder, now = Date.now()): boolean {
  const ok = wo.status === 'closed' || (wo.status === 'completed' && wo.requesterConfirmation === 'accepted');
  if (!ok) return false;
  const ref = Date.parse((wo.status === 'closed' ? wo.closedAt : wo.completedDate) ?? '');
  return Number.isNaN(ref) || now - ref <= RATING_WINDOW_DAYS * DAY;
}

/** The pending question of an info request thread (not answered, not cancelled). */
export function openInfoRequest<T extends { answer?: string; cancelledAt?: string }>(list: T[]): T | undefined {
  return list.find((r) => !r.answer && !r.cancelledAt);
}

export function absoluteStatusUrl(path: string, origin: string): string {
  return `${origin.replace(/\/$/, '')}${path.startsWith('/') ? path : `/${path}`}`;
}
