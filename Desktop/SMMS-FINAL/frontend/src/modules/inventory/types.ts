// Shapes of the inventory API (see backend inventory/dto/Dtos.java). Lowercase enums, ISO dates.

export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CenterRef {
  id: string;
  name: string;
  nameAr?: string;
  code?: string;
}

export interface UserRef {
  id: string;
  fullName: string;
  fullNameAr?: string;
}

export interface ItemRef {
  id: string;
  sku: string;
  name: string;
  nameAr?: string;
  uom?: string;
}

export interface WarehouseRef {
  id: string;
  code: string;
  name: string;
  nameAr?: string;
}

export interface Warehouse {
  id: string;
  centerId: string;
  centerName?: string;
  code: string;
  name: string;
  nameAr: string;
  active: boolean;
}

export interface BalanceLine {
  warehouse: WarehouseRef;
  onHand: number;
  reserved: number;
  available: number;
  minQty?: number;
}

export interface InventoryItem {
  id: string;
  sku: string;
  name: string;
  nameAr?: string;
  category?: string;
  uom?: string;
  uomId?: string;
  reorderLevel?: number;
  /** Stock figures are omitted for roles that may not read balances. */
  totalOnHand?: number;
  totalReserved?: number;
  totalAvailable?: number;
  lowStock?: boolean;
  active: boolean;
  center?: CenterRef;
  balances?: BalanceLine[];
}

export interface Uom {
  id: string;
  code: string;
  name: string;
  nameAr: string;
  decimals: number;
  active: boolean;
}

export type MovementKind =
  | 'opening'
  | 'receipt'
  | 'issue'
  | 'return_in'
  | 'adjustment_in'
  | 'adjustment_out'
  | 'reversal_in'
  | 'reversal_out';

export const MOVEMENT_KINDS: MovementKind[] = [
  'opening',
  'receipt',
  'issue',
  'return_in',
  'adjustment_in',
  'adjustment_out',
  'reversal_in',
  'reversal_out',
];

export interface Movement {
  id: string;
  kind: MovementKind;
  item: ItemRef;
  warehouse: WarehouseRef;
  quantity: number;
  balanceAfter: number;
  workOrderId?: string;
  ticketNumber?: string;
  materialRequestId?: string;
  poId?: string;
  reversalOfId?: string;
  reversedById?: string;
  reasonCode?: string;
  note?: string;
  reference?: string;
  actor?: UserRef;
  createdAt: string;
}

export interface StockCard {
  item: ItemRef;
  warehouse: WarehouseRef;
  from?: string;
  to?: string;
  openingBalance: number;
  closingBalance: number;
  entries: Page<Movement>;
}

export type AdjustmentReason = 'count_correction' | 'damage' | 'expired' | 'found' | 'other';
export const ADJUSTMENT_REASONS: AdjustmentReason[] = ['count_correction', 'damage', 'expired', 'found', 'other'];

export interface ReceiptRequest {
  warehouseId: string;
  reference?: string;
  note?: string;
  lines: { itemId: string; quantity: number }[];
}

export interface AdjustmentRequest {
  warehouseId: string;
  itemId: string;
  direction: 'in' | 'out';
  quantity: number;
  reasonCode: AdjustmentReason;
  note: string;
}

export interface ItemInput {
  centerId?: string;
  sku?: string;
  name: string;
  nameAr: string;
  category?: string;
  uomId?: string;
  reorderLevel?: number;
  active?: boolean;
}

export interface WarehouseInput {
  centerId: string;
  code: string;
  name: string;
  nameAr: string;
  active: boolean;
}
