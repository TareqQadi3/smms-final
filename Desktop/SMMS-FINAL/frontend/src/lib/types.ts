export type UserRole =
  | 'system_admin' | 'region_supervisor' | 'hospital_supervisor'
  | 'control_room' | 'technician' | 'maintenance_manager'
  | 'electrical_manager' | 'warehouse' | 'operating_company_manager'
  | 'resident' | 'department' | 'other';

export type TicketStatus =
  | 'new' | 'under_review' | 'assigned' | 'in_progress'
  | 'waiting_materials' | 'waiting_approval' | 'completed' | 'closed' | 'cancelled';

export type TicketPriority = 'critical' | 'high' | 'medium' | 'low';

// ── Auth ──────────────────────────────────────────────────
export interface AuthUser {
  id: string;
  username: string;
  fullName: string;
  fullNameAr?: string;
  email?: string;
  phone?: string;
  role: UserRole;
  active: boolean;
  center?: { id: string; name: string; nameAr: string; code: string };
}

// keep alias for legacy usage
export type User = AuthUser;

// ── Region ─────────────────────────────────────────────────
export interface Region {
  id: string;
  name: string;
  nameAr: string;
  code: string;
  active: boolean;
}

// ── Center ─────────────────────────────────────────────────
export interface Center {
  id: string;
  name: string;
  nameAr: string;
  code: string;
  address?: string;
  active: boolean;
  region?: {
    id: string;
    name: string;
    nameAr: string;
    code: string;
  };
}

// ── Control Room ───────────────────────────────────────────
export interface ControlRoom {
  id: string;
  name: string;
  nameAr: string;
  code?: string;
  type: string;
  active: boolean;
  center?: {
    id: string;
    name: string;
    nameAr: string;
  };
}

// ── Work Order ─────────────────────────────────────────────
export interface WorkOrder {
  id: string;
  ticketNumber: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  subType: string;
  locationDetail?: string;
  requestedBy?: { id: string; fullName: string };
  assignedTo?:  { id: string; fullName: string };
  controlRoom?: { id: string; name: string };
  center?:      { id: string; name: string };
  createdAt: string;
  completedDate?: string;
  closedAt?: string;
  slaDeadline?: string;
  slaBreached?: boolean;
  techReport?: string;
  warehouseNotes?: string;
  rejectionReason?: string;
  needsMaterials?: boolean;
  locked?: boolean;
}

export interface TicketHistory {
  id: string;
  fromStatus?: TicketStatus;
  toStatus: TicketStatus;
  actorRole: UserRole;
  actor?: { fullName: string };
  note?: string;
  stage: string;
  createdAt: string;
}

// ── Dashboard ──────────────────────────────────────────────
export interface DashboardStats {
  new: number;
  under_review: number;
  assigned: number;
  in_progress: number;
  waiting_materials: number;
  waiting_approval: number;
  completed: number;
  closed: number;
  cancelled: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
