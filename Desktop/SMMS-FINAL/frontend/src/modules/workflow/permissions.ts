import type { WorkOrderDetail } from './types';

/**
 * UI hints only. Every rule below mirrors a server-side @PreAuthorize / policy (plan 2.11);
 * the backend remains the only protection and answers 403/404/422 regardless of what is shown.
 */
type Role = string | undefined | null;
const has = (roles: readonly string[], role: Role) => !!role && roles.includes(role);

export const EMERGENCY_DECLARE_ROLES = ['control_room', 'maintenance_manager', 'hospital_supervisor', 'system_admin'] as const;
export const EMERGENCY_CLEAR_ROLES = ['maintenance_manager', 'hospital_supervisor', 'system_admin'] as const;
export const ESCALATE_ROLES = ['control_room', 'maintenance_manager', 'hospital_supervisor', 'electrical_manager', 'system_admin'] as const;
export const ESCALATION_READ_ROLES = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'control_room',
  'maintenance_manager', 'electrical_manager', 'operating_company_manager', 'warehouse',
] as const;
export const NOTE_ROLES = ['technician', 'control_room', 'maintenance_manager', 'hospital_supervisor', 'system_admin'] as const;
export const RATING_READ_ROLES = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'control_room', 'maintenance_manager',
  'electrical_manager', 'operating_company_manager', 'warehouse', 'resident', 'department',
] as const;
export const APPROVAL_DECISION_ROLES = ['operating_company_manager'] as const;
export const ASSET_READ_ROLES = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager',
  'operating_company_manager', 'control_room', 'warehouse', 'technician', 'department',
] as const;
/** Emergency can only be declared on an existing ticket (the create API has no emergency flag). */
export const EMERGENCY_AT_CREATE_ROLES = EMERGENCY_DECLARE_ROLES;

const TERMINAL = ['completed', 'closed', 'cancelled'];
export const isTerminal = (status?: string) => !!status && TERMINAL.includes(status);

export const canDeclareEmergency = (role: Role, wo: WorkOrderDetail) =>
  has(EMERGENCY_DECLARE_ROLES, role) && !wo.isEmergency && !isTerminal(wo.status);
export const canClearEmergency = (role: Role, wo: WorkOrderDetail) =>
  has(EMERGENCY_CLEAR_ROLES, role) && !!wo.isEmergency;
export const canReadEscalations = (role: Role) => has(ESCALATION_READ_ROLES, role);
export const canEscalate = (role: Role, wo: WorkOrderDetail) =>
  has(ESCALATE_ROLES, role) && !isTerminal(wo.status) && !wo.slaPaused && (wo.escalationLevel ?? 0) < 3;
export const canReadRating = (role: Role) => has(RATING_READ_ROLES, role);
export const canDecideApproval = (role: Role) => has(APPROVAL_DECISION_ROLES, role);
export const canReadAssets = (role: Role) => has(ASSET_READ_ROLES, role);
export const canDeclareEmergencyAtCreate = (role: Role) => has(EMERGENCY_AT_CREATE_ROLES, role);

export const canAddNote = (role: Role, userId: string | undefined, wo: WorkOrderDetail) => {
  if (!has(NOTE_ROLES, role)) return false;
  if (role === 'technician') {
    return wo.assignedTo?.id === userId && ['assigned', 'in_progress', 'waiting_materials'].includes(wo.status);
  }
  return true;
};

/** Role + status gating of the sidebar actions (moved unchanged from the old detail page). */
export function deriveActions(wo: WorkOrderDetail, user: { id?: string; role?: string } | null) {
  const role = user?.role ?? '';
  const infoPending = wo.currentStage === 'info_requested';
  return {
    infoPending,
    canAssign: ['control_room', 'maintenance_manager', 'system_admin'].includes(role) && wo.status === 'under_review' && !infoPending,
    canRequestInfo: ['control_room', 'maintenance_manager'].includes(role) && wo.status === 'under_review' && !infoPending,
    canProvideInfo: infoPending && wo.status === 'under_review' && !!user?.id && wo.requestedBy?.id === user.id,
    canWarehouse: role === 'warehouse' && wo.status === 'waiting_materials',
    canApproval: canDecideApproval(role) && wo.status === 'waiting_approval',
    canReopen: ['control_room', 'system_admin'].includes(role) && wo.status === 'completed',
    transitions: getTransitions(role, wo.status),
  };
}

/** Mirrors WorkflowEngine (backend is authoritative). */
export function getTransitions(role: string, status: string): string[] {
  const map: Record<string, Record<string, string[]>> = {
    control_room: {
      new: ['under_review', 'cancelled'], under_review: ['cancelled'], assigned: ['cancelled'],
      in_progress: ['cancelled'], completed: ['closed'],
    },
    technician: { assigned: ['in_progress'], in_progress: ['waiting_materials', 'completed'] },
    maintenance_manager: {
      new: ['cancelled'], under_review: ['cancelled'], assigned: ['cancelled'],
      in_progress: ['cancelled'], completed: ['closed'],
    },
    hospital_supervisor: { new: ['cancelled'] },
    system_admin: {
      new: ['under_review', 'cancelled'], under_review: ['cancelled'], assigned: ['in_progress', 'cancelled'],
      in_progress: ['completed', 'waiting_materials', 'cancelled'],
      waiting_materials: ['waiting_approval', 'in_progress'],
      waiting_approval: ['in_progress', 'waiting_materials'], completed: ['closed'],
    },
  };
  return map[role]?.[status] || [];
}
