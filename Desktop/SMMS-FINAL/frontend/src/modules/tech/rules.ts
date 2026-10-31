import type { TicketPriority, TicketStatus } from '@/lib/types';
import type { MaterialLine, TechTransition, TechWorkOrder } from './types';

/**
 * UI mirror of the server's transition matrix for the technician role (WorkflowEngine):
 *   assigned -> in_progress, in_progress -> waiting_materials | completed.
 * waiting_materials -> in_progress belongs to the warehouse, so it is NOT offered here.
 * The buttons are only a hint; a stale screen still gets a clean 403/422 from the server.
 */
export function technicianTransitions(status: TicketStatus): TechTransition[] {
  switch (status) {
    case 'assigned': return ['in_progress'];
    case 'in_progress': return ['waiting_materials', 'completed'];
    default: return [];
  }
}

/** POST /notes is allowed to the assigned technician in these statuses. */
export function canAddNote(status: TicketStatus): boolean {
  return status === 'assigned' || status === 'in_progress' || status === 'waiting_materials';
}

export const ACTIVE_STATUSES: TicketStatus[] = ['assigned', 'in_progress', 'waiting_materials', 'waiting_approval'];
export const HISTORY_STATUSES: TicketStatus[] = ['completed', 'closed', 'cancelled'];

const PRIORITY_RANK: Record<TicketPriority, number> = { critical: 0, high: 1, medium: 2, low: 3 };

/** Emergency first, then priority, then the earliest SLA deadline, then the oldest ticket. */
export function compareQueue(a: TechWorkOrder, b: TechWorkOrder): number {
  const e = Number(!!b.isEmergency) - Number(!!a.isEmergency);
  if (e) return e;
  const p = (PRIORITY_RANK[a.priority] ?? 9) - (PRIORITY_RANK[b.priority] ?? 9);
  if (p) return p;
  const sa = a.slaDeadline ? Date.parse(a.slaDeadline) : Infinity;
  const sb = b.slaDeadline ? Date.parse(b.slaDeadline) : Infinity;
  if (sa !== sb) return sa < sb ? -1 : 1;
  return Date.parse(a.createdAt) - Date.parse(b.createdAt);
}

export function outstanding(line: MaterialLine): number {
  const v = (line.qtyIssued ?? 0) - (line.qtyConsumed ?? 0) - (line.qtyReturned ?? 0);
  return Math.round(v * 1000) / 1000;
}

/** Parses a quantity typed by the user (accepts Arabic-Indic digits and a decimal comma). Null if not a positive number. */
export function parseQuantity(raw: string): number | null {
  const ascii = raw
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06f0))
    .replace('٫', '.')
    .replace(',', '.')
    .trim();
  if (!/^\d+(\.\d{1,3})?$/.test(ascii)) return null;
  const n = Number(ascii);
  return n > 0 && n <= 1_000_000 ? n : null;
}
