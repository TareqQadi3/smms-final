import type { TicketPriority, TicketStatus } from '@/lib/types';

export interface PersonRef { id: string; fullName: string; fullNameAr?: string }
export interface PlaceRef { id: string; name: string; nameAr?: string }

/** Fields of GET /api/work-orders[/{id}] the technician area reads (the server never sends more than the caller may see). */
export interface TechWorkOrder {
  id: string;
  ticketNumber: string;
  status: TicketStatus;
  currentStage?: string;
  priority: TicketPriority;
  subType?: string;
  title: string;
  description?: string;
  locationDetail?: string;
  needsMaterials?: boolean;
  slaBreached?: boolean;
  slaPaused?: boolean;
  slaDeadline?: string;
  locked?: boolean;
  isEmergency?: boolean;
  createdAt: string;
  completedDate?: string;
  closedAt?: string;
  requestedBy?: PersonRef;
  assignedTo?: PersonRef;
  center?: PlaceRef;
  controlRoom?: PlaceRef;
  techReport?: string;
  warehouseNotes?: string;
  rejectionReason?: string;
  assetId?: string;
  asset?: { id: string; assetCode?: string | null; name: string; nameAr?: string | null } | null;
}

export interface HistoryEntry {
  id: string;
  fromStatus?: TicketStatus;
  toStatus: TicketStatus;
  stage?: string;
  note?: string;
  createdAt: string;
  actor?: { id: string; fullName: string; role?: string };
}

export type PhotoPhase = 'before' | 'after';

export interface Attachment {
  id: string;
  fileName: string;
  fileSize?: number;
  mimeType?: string;
  createdAt: string;
  uploadedById?: string;
  photoPhase?: PhotoPhase | null;
}

export interface AssetWorkOrderRow {
  id: string;
  ticketNumber: string;
  title: string;
  status: TicketStatus;
  priority: TicketPriority;
  createdAt: string;
  completedDate?: string;
  techReport?: string;
  assignedToName?: string;
  preventive?: boolean;
}

export type MaterialRequestStatus =
  | 'submitted' | 'approved' | 'rejected' | 'reserved' | 'issued' | 'closed' | 'cancelled';

export interface MaterialRequestSummary {
  id: string;
  requestNumber: string;
  status: MaterialRequestStatus;
  workOrder?: { id: string; ticketNumber: string; status: TicketStatus };
  createdAt: string;
}

export interface MaterialLine {
  id: string;
  item: { id: string; sku: string; name: string; nameAr?: string | null; uom?: string | null };
  qtyRequested: number;
  qtyApproved: number;
  qtyReserved: number;
  qtyIssued: number;
  qtyConsumed: number;
  qtyReturned: number;
}

export interface MaterialRequest extends MaterialRequestSummary {
  note?: string;
  decisionNote?: string;
  cancelReason?: string;
  lines: MaterialLine[];
  version?: number;
}

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  body?: string;
  workOrderId?: string | null;
  read: boolean;
  createdAt: string;
}

/** What the technician may do per status (the server enforces it; this only decides which buttons to show). */
export type TechTransition = 'in_progress' | 'waiting_materials' | 'completed';
