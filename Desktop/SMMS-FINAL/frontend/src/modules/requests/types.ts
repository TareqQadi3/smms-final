import type { TicketPriority, TicketStatus } from '@/lib/types';

export type Confirmation = 'accepted' | 'disputed' | null;

export interface RequestWorkOrder {
  id: string;
  ticketNumber: string;
  status: TicketStatus;
  currentStage?: string;
  priority: TicketPriority;
  title: string;
  description?: string;
  locationDetail?: string;
  createdAt: string;
  completedDate?: string;
  closedAt?: string;
  techReport?: string;
  rejectionReason?: string;
  isEmergency?: boolean;
  requesterConfirmation?: Confirmation;
  requesterConfirmedAt?: string | null;
  center?: { id: string; name: string; nameAr?: string };
  asset?: { id: string; name: string; nameAr?: string | null; assetCode?: string | null } | null;
}

export interface RequestHistoryEntry {
  id: string;
  fromStatus?: TicketStatus;
  toStatus: TicketStatus;
  stage?: string;
  createdAt: string;
}

export interface UserRef { id: string; fullName: string; fullNameAr?: string | null }

export interface InfoRequest {
  id: string;
  question: string;
  requestedBy?: UserRef;
  requestedAt: string;
  answer?: string;
  answeredBy?: UserRef;
  answeredAt?: string;
  cancelledAt?: string;
  pausedSeconds?: number;
}

export interface Rating { id: string; score: number; comment?: string; createdAt: string }

export interface StatusLink { token: string; path: string; expiresAt: string }

/** Exactly what GET /api/public/request-status/{token} returns. Nothing else is ever shown. */
export interface PublicStatus {
  ticketNumber: string;
  status: string;
  statusGroup: 'received' | 'in_progress' | 'waiting' | 'done' | 'cancelled';
  createdAt: string;
  lastUpdatedAt: string;
  completed: boolean;
}
