import type { CenterRef, ItemRef, UserRef, WarehouseRef } from '@/modules/inventory/types';

export interface Supplier {
  id: string;
  code: string;
  name: string;
  nameAr?: string;
  phone?: string;
  email?: string;
  taxNumber?: string;
  active: boolean;
  createdAt?: string;
}

export interface SupplierInput {
  code: string;
  name: string;
  nameAr?: string;
  phone?: string;
  email?: string;
  taxNumber?: string;
}

export interface SupplierRef {
  id: string;
  code: string;
  name: string;
  nameAr?: string;
}

export type PoStatus =
  | 'draft'
  | 'submitted'
  | 'approved'
  | 'rejected'
  | 'partially_received'
  | 'received'
  | 'cancelled'
  | 'closed';

export const PO_STATUSES: PoStatus[] = [
  'draft',
  'submitted',
  'approved',
  'rejected',
  'partially_received',
  'received',
  'cancelled',
  'closed',
];

export interface PoSummary {
  id: string;
  poNumber: string;
  status: PoStatus;
  center?: CenterRef;
  supplier?: SupplierRef;
  warehouseId?: string;
  expectedDate?: string;
  total?: number;
  createdAt: string;
}

export interface PoLine {
  id: string;
  lineNo: number;
  item: ItemRef;
  qtyOrdered: number;
  qtyReceived: number;
  unitPrice?: number;
}

export interface PoReceipt {
  id: string;
  poId?: string;
  receivedAt: string;
  receivedBy?: UserRef;
  note?: string;
  lines: { poLineId: string; quantity: number; movementId?: string }[];
}

export interface PurchaseOrder {
  id: string;
  poNumber: string;
  status: PoStatus;
  center?: CenterRef;
  supplier?: SupplierRef;
  warehouse?: WarehouseRef;
  workOrderId?: string;
  materialRequestId?: string;
  expectedDate?: string;
  notes?: string;
  createdBy?: UserRef;
  submittedAt?: string;
  approvedBy?: UserRef;
  approvedAt?: string;
  decisionReason?: string;
  total?: number;
  lines: PoLine[];
  receipts: PoReceipt[];
  version?: number;
}

export interface PoInput {
  centerId: string;
  supplierId: string;
  warehouseId: string;
  expectedDate?: string;
  notes?: string;
  workOrderId?: string;
  materialRequestId?: string;
  lines: { itemId: string; qtyOrdered: number; unitPrice?: number }[];
  version?: number;
}
