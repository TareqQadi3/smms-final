import type { TicketHistory, WorkOrder } from '@/lib/types';

export interface UserRef { id: string; fullName: string; fullNameAr?: string | null }
export interface AssetRef { id: string; assetCode?: string | null; name: string; nameAr?: string | null }

/** WorkOrderResponse after feature program 1 (lib/types.ts is shared and not edited by this module). */
export type WorkOrderDetail = WorkOrder & {
  currentStage?: string;
  requestedBy?: { id: string; fullName: string; fullNameAr?: string | null };
  assetId?: string | null;
  asset?: AssetRef | null;
  departmentId?: string | null;
  isEmergency?: boolean;
  emergencyDeclaredAt?: string | null;
  slaPaused?: boolean;
  slaPausedSeconds?: number;
  requesterConfirmation?: 'accepted' | 'disputed' | null;
  requesterConfirmedAt?: string | null;
  escalationLevel?: number;
  reopenedCount?: number;
};

export type HistoryEntry = TicketHistory;

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

export interface Escalation {
  id: string;
  level: number;
  trigger: string;
  triggeredBy?: UserRef | null;
  reason?: string | null;
  notifiedCount?: number | null;
  createdAt: string;
}

export interface Rating { id: string; score: number; comment?: string; createdAt: string }

export interface AttachmentMeta {
  id: string;
  fileName: string;
  fileSize?: number | null;
  mimeType?: string | null;
  createdAt: string;
  uploadedById?: string | null;
  photoPhase?: 'before' | 'after' | null;
}

export interface NotificationPreferences {
  emailEnabled: boolean;
  smsEnabled: boolean;
  types?: string[] | null;
  emailAvailable: boolean;
  smsAvailable: boolean;
}

export type DeliveryStatus = 'pending' | 'failed' | 'sent' | 'dead' | 'not_configured' | 'skipped_no_address';
export type DeliveryChannel = 'email' | 'sms';

export interface DeliveryRow {
  id: string;
  notificationId?: string | null;
  userId?: string | null;
  channel: DeliveryChannel;
  destinationMasked?: string | null;
  status: DeliveryStatus;
  attempts: number;
  lastError?: string | null;
  provider?: string | null;
  createdAt: string;
  sentAt?: string | null;
}

/** Note: this endpoint's page uses `page`, not `number`. */
export interface DeliveryPage {
  content: DeliveryRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProviderStatus {
  channel: DeliveryChannel;
  provider: string;
  enabled: boolean;
  configured: boolean;
  problems: string[];
}

export interface AssetOption { id: string; assetCode?: string | null; name: string; nameAr?: string | null; location?: string | null }
