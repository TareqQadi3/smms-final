import type { CenterRef, ItemRef, UserRef, WarehouseRef } from '@/modules/inventory/types';

export type MrStatus = 'submitted' | 'approved' | 'rejected' | 'reserved' | 'issued' | 'closed' | 'cancelled';
export const MR_STATUSES: MrStatus[] = ['submitted', 'approved', 'rejected', 'reserved', 'issued', 'closed', 'cancelled'];

export interface WorkOrderSummary {
  id: string;
  ticketNumber: string;
  status: string;
}

export interface MrLine {
  id: string;
  item: ItemRef;
  qtyRequested: number;
  qtyApproved: number;
  qtyReserved: number;
  qtyIssued: number;
  qtyConsumed: number;
  qtyReturned: number;
}

export interface MaterialRequest {
  id: string;
  requestNumber: string;
  status: MrStatus;
  workOrder?: WorkOrderSummary;
  warehouse?: WarehouseRef;
  center?: CenterRef;
  requestedBy?: UserRef;
  decidedBy?: UserRef;
  decidedAt?: string;
  decisionNote?: string;
  note?: string;
  cancelReason?: string;
  lines: MrLine[];
  version?: number;
  createdAt: string;
}

export interface MrSummary {
  id: string;
  requestNumber: string;
  status: MrStatus;
  workOrder?: WorkOrderSummary;
  warehouseId?: string;
  center?: CenterRef;
  requestedBy?: UserRef;
  createdAt: string;
}

export interface LineQty {
  lineId: string;
  quantity: number;
}

export interface CreateMrInput {
  warehouseId: string;
  note?: string;
  lines: { itemId: string; quantity: number }[];
}
